package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathLinksActionTest {

    @TempDir
    Path root;

    private ApplyContext context(String linkSpec) {
        return context(root, linkSpec);
    }

    private ApplyContext context(Path packageDir, String linkSpec) {
        ServiceSpec spec = ServiceSpec.builder(packageDir)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(new ArrayList<String>(Arrays.asList(linkSpec)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        SystemdTarget target = new SystemdTarget(root.resolve("units"), root.resolve("env"));
        return new ApplyContext(spec, target, new RecordingRunner(), false);
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("probe-link");
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * path-links 的目标必须是 POSIX 绝对路径（以 / 开头），Windows 上会被解析器拒绝，
     * 因此这些用例只能在类 Unix 环境执行。
     */
    private static void assumePosixTargets() {
        Assumptions.assumeTrue(File.separatorChar == '/', "path-links 目标只支持 POSIX 绝对路径");
    }

    private static String refuseMessage(Path target) {
        return "目标已存在且不是指向源的软链接，请人工处理: " + target;
    }

    private static void assertNoBackup(Path target) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target.getParent(), "*.bak*")) {
            assertFalse(entries.iterator().hasNext(), "不应创建 .bak 备份");
        }
    }

    @Test
    void createsLinkWhenTargetMissing() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");

        new PathLinksAction().run(context(source + ":" + target));

        assertTrue(Files.isSymbolicLink(target));
        assertEquals(source, Files.readSymbolicLink(target));
        assertNoBackup(target);
    }

    @Test
    void isIdempotentWhenLinkAlreadyPointsToSource() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");
        Files.createSymbolicLink(target, source);

        new PathLinksAction().run(context(source + ":" + target));

        assertEquals(source, Files.readSymbolicLink(target));
        assertNoBackup(target);
    }

    @Test
    void refusesExistingEmptyDirectory() throws IOException {
        assumePosixTargets();
        Path source = Files.createDirectories(root.resolve("payload"));
        Path target = Files.createDirectories(root.resolve("link"));

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));

        assertEquals(refuseMessage(target), failure.getMessage());
        assertTrue(Files.isDirectory(target));
        assertNoBackup(target);
    }

    @Test
    void refusesExistingNonEmptyDirectory() throws IOException {
        assumePosixTargets();
        Path source = Files.createDirectories(root.resolve("payload"));
        Path target = Files.createDirectories(root.resolve("link"));
        Files.write(target.resolve("old.txt"), "old".getBytes(StandardCharsets.UTF_8));

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));

        assertEquals(refuseMessage(target), failure.getMessage());
        assertEquals("old", new String(Files.readAllBytes(target.resolve("old.txt")), StandardCharsets.UTF_8));
        assertFalse(Files.isSymbolicLink(target));
        assertNoBackup(target);
    }

    @Test
    void refusesExistingRegularFile() throws IOException {
        assumePosixTargets();
        Path source = Files.createDirectories(root.resolve("payload"));
        Path target = Files.write(root.resolve("link"), "old".getBytes(StandardCharsets.UTF_8));

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));

        assertEquals(refuseMessage(target), failure.getMessage());
        assertTrue(Files.isRegularFile(target));
        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertNoBackup(target);
    }

    @Test
    void refusesSymlinkThatPointsElsewhere() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Path target = root.resolve("link");
        Files.createSymbolicLink(target, elsewhere);

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));

        assertEquals(refuseMessage(target), failure.getMessage());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals(elsewhere, Files.readSymbolicLink(target));
        assertNoBackup(target);
    }

    @Test
    void refusesDanglingSymlink() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path gone = root.resolve("gone");
        Path target = root.resolve("link");
        Files.createSymbolicLink(target, gone);

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));

        assertEquals(refuseMessage(target), failure.getMessage());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals(gone, Files.readSymbolicLink(target));
        assertNoBackup(target);
    }

    @Test
    void failsWhenTargetParentDoesNotExist() throws IOException {
        assumePosixTargets();
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("missing-dir").resolve("link.txt");

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));
        assertTrue(failure.getMessage().contains("父目录"), failure.getMessage());
    }

    // 护栏在创建链接之前触发，因此这些用例不需要软链接支持。

    @Test
    void refusesProtectedTarget() throws IOException {
        Files.createDirectories(root.resolve("payload"));

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context("payload:/etc")));

        assertTrue(failure.getMessage().contains("path-links[0]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("被保护的系统目录"), failure.getMessage());
    }

    @Test
    void refusesTargetEqualToSource() {
        // 以当前目录为包目录，让 source 与 target 的绝对化落在同一盘符（Windows）。
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(
                        context(cwd, "/tmp/xac-same:/tmp/xac-same")));

        assertTrue(failure.getMessage().contains("目标不能与源相同"), failure.getMessage());
    }

    @Test
    void failsWhenSourceDoesNotExist() {
        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context("missing:/opt/xac-action-target")));

        assertTrue(failure.getMessage().contains("源目录不存在"), failure.getMessage());
    }

    @Test
    void refusesTargetThatIsAncestorOfPackageDir() {
        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(
                        context(Paths.get("/opt/demo"), "cfg:/opt")));

        assertTrue(failure.getMessage().contains("或其上级"), failure.getMessage());
    }
}
