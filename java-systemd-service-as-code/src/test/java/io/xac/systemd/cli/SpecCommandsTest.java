package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
