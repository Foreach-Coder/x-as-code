package io.xac.systemd.apply;

import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreflightTest {

    @TempDir
    Path pkg;

    private ServiceSpec spec(String jarName) {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .jar(jarName)
                .java("/usr/bin/java")
                .opts(java.util.Collections.<String>emptyList())
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private SystemdTarget target() {
        return new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
    }

    /** 预检现在要查同名 unit；测试里用一个「查询成功但无内容」的 runner 与临时目标目录。 */
    private List<Violation> preflight(ServiceSpec spec, EnvironmentProbe probe) {
        return Preflight.check(spec, probe, new RecordingRunner(), target());
    }

    private static final class FakeProbe implements EnvironmentProbe {
        boolean root = true;
        boolean systemd = true;
        boolean javaExecutable = true;

        @Override
        public boolean isRoot() { return root; }

        @Override
        public boolean systemdRunning() { return systemd; }

        @Override
        public boolean fileReadable(Path path) { return Files.isRegularFile(path); }

        @Override
        public boolean executable(Path path) { return javaExecutable; }
    }

    @Test
    void passesWhenEverythingIsFine() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        assertTrue(preflight(spec("app.jar"), new FakeProbe()).isEmpty());
    }

    @Test
    void requiresJarToExist() {
        List<Violation> out = preflight(spec("app.jar"), new FakeProbe());
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("artifact.jar"), out.get(0).toString());
    }

    @Test
    void requiresRootAndSystemd() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.root = false;
        probe.systemd = false;
        List<Violation> out = preflight(spec("app.jar"), probe);
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).toString().contains("root"));
        assertTrue(out.get(1).toString().contains("systemd"));
    }

    @Test
    void requiresJavaToBeExecutable() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.javaExecutable = false;
        List<Violation> out = preflight(spec("app.jar"), probe);
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("runtime.java"), out.get(0).toString());
    }

    @Test
    void destroyCheckSkipsArtifactAndJava() {
        FakeProbe probe = new FakeProbe();
        probe.javaExecutable = false;

        assertTrue(Preflight.checkDestroy(probe).isEmpty());
    }

    @Test
    void destroyCheckRequiresRootAndSystemd() {
        FakeProbe probe = new FakeProbe();
        probe.root = false;
        probe.systemd = false;

        List<Violation> out = Preflight.checkDestroy(probe);

        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).message.contains("root"), out.get(0).message);
        assertTrue(out.get(1).message.contains("systemd"), out.get(1).message);
    }

    @Test
    void requiresPathLinkSourceToExist() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(Collections.singletonList("missing:/opt/xac-target"))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();

        List<Violation> out = preflight(spec, new FakeProbe());

        assertEquals(1, out.size(), out.toString());
        assertEquals("path-links[0]", out.get(0).path);
        assertTrue(out.get(0).message.contains("源目录不存在"), out.get(0).message);
    }

    // path-links 的目标必须是 POSIX 绝对路径，Windows 上会被解析器拒绝。

    private static void assumePosixTargets() {
        Assumptions.assumeTrue(File.separatorChar == '/', "path-links 目标只支持 POSIX 绝对路径");
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

    private static String refuseMessage(Path target) {
        return "目标已存在且不是指向源的软链接，请人工处理: " + target;
    }

    private ServiceSpec specWithLink(Path source, Path target) {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(Collections.singletonList(source + ":" + target))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private List<Violation> check(Path target) throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        Path source = Files.createDirectories(pkg.resolve("payload"));
        return preflight(specWithLink(source, target), new FakeProbe());
    }

    @Test
    void acceptsMissingPathLinkTarget() throws IOException {
        assumePosixTargets();
        assertTrue(check(pkg.resolve("link")).isEmpty());
    }

    @Test
    void acceptsSymlinkThatPointsAtSource() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(pkg));
        Path source = Files.createDirectories(pkg.resolve("payload"));
        Path target = pkg.resolve("link");
        Files.createSymbolicLink(target, source);

        List<Violation> out = check(target);

        assertTrue(out.isEmpty(), out.toString());
    }

    @Test
    void rejectsEmptyDirectoryTarget() throws IOException {
        assumePosixTargets();
        Path target = Files.createDirectories(pkg.resolve("link"));

        List<Violation> out = check(target);

        assertEquals(1, out.size(), out.toString());
        assertEquals("path-links[0]", out.get(0).path);
        assertEquals(refuseMessage(target), out.get(0).message);
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void rejectsNonEmptyDirectoryTarget() throws IOException {
        assumePosixTargets();
        Path target = Files.createDirectories(pkg.resolve("link"));
        Files.write(target.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));

        List<Violation> out = check(target);

        assertEquals(1, out.size(), out.toString());
        assertEquals(refuseMessage(target), out.get(0).message);
        assertTrue(Files.isRegularFile(target.resolve("keep.txt")));
    }

    @Test
    void rejectsRegularFileTarget() throws IOException {
        assumePosixTargets();
        Path target = Files.write(pkg.resolve("link"), "keep".getBytes(StandardCharsets.UTF_8));

        List<Violation> out = check(target);

        assertEquals(1, out.size(), out.toString());
        assertEquals(refuseMessage(target), out.get(0).message);
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void rejectsForeignSymlinkTarget() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(pkg));
        Path target = pkg.resolve("link");
        Files.createSymbolicLink(target, pkg.resolve("elsewhere"));

        List<Violation> out = check(target);

        assertEquals(1, out.size(), out.toString());
        assertEquals(refuseMessage(target), out.get(0).message);
        assertEquals(pkg.resolve("elsewhere"), Files.readSymbolicLink(target));
    }

    @Test
    void rejectsDanglingSymlinkTarget() throws IOException {
        assumePosixTargets();
        Assumptions.assumeTrue(supportsSymlinks(pkg));
        Path target = pkg.resolve("link");
        Files.createSymbolicLink(target, pkg.resolve("gone"));

        List<Violation> out = check(target);

        assertEquals(1, out.size(), out.toString());
        assertEquals(refuseMessage(target), out.get(0).message);
        assertTrue(Files.isSymbolicLink(target));
    }
}
