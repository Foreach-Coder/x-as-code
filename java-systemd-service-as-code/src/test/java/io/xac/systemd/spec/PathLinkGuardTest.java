package io.xac.systemd.spec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathLinkGuardTest {

    @TempDir
    Path root;

    private Path packageDir() {
        return root.resolve("pkg");
    }

    private Path unitDir() {
        return root.resolve("units");
    }

    private Path envDir() {
        return root.resolve("env");
    }

    private String check(PathLink link) {
        return PathLinkGuard.checkShape(link, unitDir(), envDir(), packageDir());
    }

    private static PathLink link(Path source, Path target) {
        return new PathLink(source + ":" + target, source, target);
    }

    @Test
    void rejectsTargetResolvedThroughSymlinkedAncestor() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.getRoot());

        String problem = check(link(root.resolve("src"), link.resolve("etc")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
    }

    @Test
    void rejectsDotDotThatEscapesThroughSymlink() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.getRoot());

        String problem = check(link(root.resolve("src"), link.resolve("..").resolve("etc")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
    }

    @Test
    void doesNotFollowSymlinkInFinalSegment() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("point");
        Files.createSymbolicLink(link, root.getRoot());

        // 移动链接本身不会动到它指向的目录，因此这里应当放行。
        assertNull(check(link(root.resolve("src"), link)));
    }

    @Test
    void acceptsMissingTarget() {
        assertNull(PathLinkGuard.checkTarget(link(root.resolve("src"), root.resolve("absent"))));
    }

    @Test
    void acceptsSymlinkThatPointsAtSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("src"));
        Path target = root.resolve("target");
        Files.createSymbolicLink(target, source);

        assertNull(PathLinkGuard.checkTarget(link(source, target)));
    }

    @Test
    void rejectsExistingDirectory() throws IOException {
        Path target = Files.createDirectories(root.resolve("target"));

        assertEquals("目标已存在且不是指向源的软链接，请人工处理: " + target,
                PathLinkGuard.checkTarget(link(root.resolve("src"), target)));
    }

    @Test
    void rejectsExistingRegularFile() throws IOException {
        Path target = Files.createFile(root.resolve("target"));

        assertEquals("目标已存在且不是指向源的软链接，请人工处理: " + target,
                PathLinkGuard.checkTarget(link(root.resolve("src"), target)));
    }

    @Test
    void rejectsSymlinkThatPointsElsewhere() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path target = root.resolve("target");
        Files.createSymbolicLink(target, root.resolve("elsewhere"));

        assertEquals("目标已存在且不是指向源的软链接，请人工处理: " + target,
                PathLinkGuard.checkTarget(link(root.resolve("src"), target)));
    }

    @Test
    void rejectsDanglingSymlink() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path target = root.resolve("target");
        Files.createSymbolicLink(target, root.resolve("gone"));

        assertEquals("目标已存在且不是指向源的软链接，请人工处理: " + target,
                PathLinkGuard.checkTarget(link(root.resolve("src"), target)));
    }

    @Test
    void rejectsTargetThatIsAncestorOfSource() {
        Path source = root.resolve("pkg").resolve("cfg").resolve("app");

        String problem = check(link(source, root.resolve("pkg").resolve("cfg")));

        assertNotNull(problem);
        assertTrue(problem.contains("源目录的上级目录"), problem);
    }

    @Test
    void rejectsTargetUnderUnitDir() {
        Path target = unitDir().resolve("demo.service");

        String problem = check(link(root.resolve("src"), target));

        assertNotNull(problem);
        assertTrue(problem.contains("unit 目录"), problem);
    }

    @Test
    void rejectsTargetUnderProtectedDir() {
        String problem = check(link(root.resolve("src"), Paths.get("/etc/passwd")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
    }

    @Test
    void rejectsTargetEqualToPackageDirReachedThroughSymlinkedParent() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Files.createDirectories(root.resolve("real").resolve("pkg"));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.resolve("real"));
        Path pkgThroughLink = link.resolve("pkg");

        // 包目录经符号链接到达；目标解析后正是真实的包目录，旧实现只解析目标会漏掉。
        String problem = PathLinkGuard.checkShape(
                link(pkgThroughLink.resolve("data"), pkgThroughLink),
                unitDir(), envDir(), pkgThroughLink);

        assertNotNull(problem);
        assertTrue(problem.contains("包目录"), problem);
    }

    @Test
    void rejectsTargetThatIsRealParentOfSymlinkedPackageDir() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Files.createDirectories(root.resolve("real").resolve("pkg"));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.resolve("real"));
        Path pkgThroughLink = link.resolve("pkg");
        Path realParent = root.resolve("real");

        // 词法上看 target（real）不是 pkgThroughLink 的上级；真实路径上它是。
        String problem = PathLinkGuard.checkShape(
                link(pkgThroughLink.resolve("data"), realParent),
                unitDir(), envDir(), pkgThroughLink);

        assertNotNull(problem);
        assertTrue(problem.contains("上级"), problem);
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
}
