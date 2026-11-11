package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCommandTest {

    @TempDir
    Path pkg;

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

    private void writeSpec(Path target) throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "path-links:",
                "  - ./payload:" + target,
                "");
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private SystemdTarget target() {
        return new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
    }

    private int apply(boolean dryRun) {
        return apply(dryRun, new RecordingRunner());
    }

    private int apply(boolean dryRun, CommandRunner runner) {
        return ApplyCommand.run(pkg, dryRun, new FakeProbe(), runner, target(),
                new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void refusesExistingPathLinkTargetWithExitTwo() throws IOException {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/',
                "path-links 目标只支持 POSIX 绝对路径");
        Files.createFile(pkg.resolve("app.jar"));
        Files.createDirectories(pkg.resolve("payload"));
        Path target = Files.createDirectories(pkg.resolve("link"));
        Files.write(target.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        writeSpec(target);

        assertEquals(2, apply(false));

        assertTrue(Files.isDirectory(target));
        assertEquals("keep", new String(Files.readAllBytes(target.resolve("keep.txt")),
                StandardCharsets.UTF_8));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pkg, "*.bak*")) {
            assertFalse(entries.iterator().hasNext(), "不应创建 .bak 备份");
        }
    }

    @Test
    void dryRunRefusesExistingPathLinkTargetWithExitTwo() throws IOException {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/',
                "path-links 目标只支持 POSIX 绝对路径");
        Files.createFile(pkg.resolve("app.jar"));
        Files.createDirectories(pkg.resolve("payload"));
        Path target = Files.createDirectories(pkg.resolve("link"));
        writeSpec(target);

        assertEquals(2, apply(true));

        assertTrue(Files.isDirectory(target));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pkg, "*.bak*")) {
            assertFalse(entries.iterator().hasNext(), "不应创建 .bak 备份");
        }
        assertFalse(Files.exists(pkg.resolve("xac.yaml.bak")));
    }
}
