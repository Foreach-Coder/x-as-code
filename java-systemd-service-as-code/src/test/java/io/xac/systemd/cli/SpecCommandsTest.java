package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecCommandsTest {

    @TempDir
    Path pkg;

    private void writeSpec() throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: missing.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private void writeSpecWithMissingLinkSource() throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "path-links:",
                "  - missing:/opt/xac-target",
                "");
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasPath(List<Violation> violations, String path) {
        for (Violation violation : violations) {
            if (path.equals(violation.path)) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeProbe implements EnvironmentProbe {
        @Override
        public boolean isRoot() { return true; }

        @Override
        public boolean systemdRunning() { return true; }

        @Override
        public boolean fileReadable(Path path) { return Files.isRegularFile(path); }

        @Override
        public boolean executable(Path path) { return true; }
    }

    @Test
    void destroyLoadDoesNotRequireArtifactJar() throws IOException {
        writeSpec();

        SpecCommands.Loaded loaded = SpecCommands.loadForDestroy(pkg, new FakeProbe());

        assertNotNull(loaded.spec);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
    }

    @Test
    void applyLoadStillRequiresArtifactJar() throws IOException {
        writeSpec();

        SpecCommands.Loaded loaded = SpecCommands.load(pkg, new FakeProbe());

        assertNull(loaded.spec);
        assertEquals(1, loaded.violations.size(), loaded.violations.toString());
        assertEquals("artifact.jar", loaded.violations.get(0).path);
    }

    /** status / logs 只走 parse，不检查源目录是否存在。 */
    @Test
    void statusAndLogsParseAcceptsMissingPathLinkSource() throws IOException {
        writeSpecWithMissingLinkSource();

        SpecCommands.Loaded loaded = SpecCommands.parse(pkg);

        assertNotNull(loaded.spec);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
    }

    @Test
    void destroyLoadAcceptsMissingPathLinkSource() throws IOException {
        writeSpecWithMissingLinkSource();

        SpecCommands.Loaded loaded = SpecCommands.loadForDestroy(pkg, new FakeProbe());

        assertNotNull(loaded.spec);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
    }

    @Test
    void applyLoadRejectsMissingPathLinkSource() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writeSpecWithMissingLinkSource();

        SpecCommands.Loaded loaded = SpecCommands.load(pkg, new FakeProbe());

        assertNull(loaded.spec);
        assertTrue(hasPath(loaded.violations, "path-links[0]"), loaded.violations.toString());
    }

    @Test
    void applyLoadRejectsExistingPathLinkTarget() throws IOException {
        assumePosixTargets();
        Files.createFile(pkg.resolve("app.jar"));
        Files.createDirectories(pkg.resolve("payload"));
        Path target = Files.createDirectories(pkg.resolve("link"));
        writeSpecWithLink("./payload:" + target);

        SpecCommands.Loaded loaded = SpecCommands.load(pkg, new FakeProbe());

        assertNull(loaded.spec);
        assertEquals(1, loaded.violations.size(), loaded.violations.toString());
        assertTrue(hasPath(loaded.violations, "path-links[0]"), loaded.violations.toString());
        assertTrue(loaded.violations.get(0).message.contains("请人工处理"),
                loaded.violations.get(0).message);
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void destroyLoadAcceptsExistingPathLinkTarget() throws IOException {
        assumePosixTargets();
        Files.createDirectories(pkg.resolve("payload"));
        Path target = Files.createDirectories(pkg.resolve("link"));
        writeSpecWithLink("./payload:" + target);

        SpecCommands.Loaded loaded = SpecCommands.loadForDestroy(pkg, new FakeProbe());

        assertNotNull(loaded.spec);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void parseAcceptsExistingPathLinkTarget() throws IOException {
        assumePosixTargets();
        Files.createDirectories(pkg.resolve("payload"));
        Path target = Files.createDirectories(pkg.resolve("link"));
        writeSpecWithLink("./payload:" + target);

        SpecCommands.Loaded loaded = SpecCommands.parse(pkg);

        assertNotNull(loaded.spec);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertTrue(Files.isDirectory(target));
    }

    private void writeSpecWithLink(String link) throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "path-links:",
                "  - " + link,
                "");
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assumePosixTargets() {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/',
                "path-links 目标只支持 POSIX 绝对路径");
    }
}
