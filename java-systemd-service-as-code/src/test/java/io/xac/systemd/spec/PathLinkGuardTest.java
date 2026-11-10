package io.xac.systemd.spec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    @Test
    void rejectsTargetResolvedThroughSymlinkedAncestor() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.getRoot());

        String problem = check(PathLink.of(root.resolve("src"), link.resolve("etc")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
    }

    @Test
    void rejectsDotDotThatEscapesThroughSymlink() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, root.getRoot());

        String problem = check(PathLink.of(root.resolve("src"), link.resolve("..").resolve("etc")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
    }

    @Test
    void doesNotFollowSymlinkInFinalSegment() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path link = root.resolve("point");
        Files.createSymbolicLink(link, root.getRoot());

        // 移动链接本身不会动到它指向的目录，因此这里应当放行。
        assertNull(check(PathLink.of(root.resolve("src"), link)));
    }

    @Test
    void rejectsTargetUnderUnitDir() {
        Path target = unitDir().resolve("demo.service");

        String problem = check(PathLink.of(root.resolve("src"), target));

        assertNotNull(problem);
        assertTrue(problem.contains("unit 目录"), problem);
    }

    @Test
    void rejectsTargetUnderProtectedDir() {
        String problem = check(PathLink.of(root.resolve("src"), Paths.get("/etc/passwd")));

        assertNotNull(problem);
        assertTrue(problem.contains("被保护"), problem);
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
