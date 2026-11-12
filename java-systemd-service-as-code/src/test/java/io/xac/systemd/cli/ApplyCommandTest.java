package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCommandTest {

    private static final String SHOW_PROPERTY = "--property=LoadState,ActiveState,SubState,"
            + "UnitFileState,MainPID,ActiveEnterTimestamp,FragmentPath";

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

    private void writePlainSpec() throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private SystemdTarget target() {
        return new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
    }

    private Path unitPath() {
        return target().unitDir.resolve("xac.demo.service");
    }

    private void createTargetDirs() throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
    }

    private static final class Captured {
        final int code;
        final String output;

        Captured(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private Captured capture(boolean dryRun, CommandRunner runner) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = ApplyCommand.run(pkg, dryRun, new FakeProbe(), runner, target(),
                new PrintStream(buffer, true, "UTF-8"));
        return new Captured(code, buffer.toString("UTF-8"));
    }

    private int apply(boolean dryRun) throws IOException {
        return apply(dryRun, new RecordingRunner());
    }

    private int apply(boolean dryRun, CommandRunner runner) throws IOException {
        return capture(dryRun, runner).code;
    }

    private static RecordingRunner runnerShowing(String stdout) {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, stdout, ""));
        return runner;
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

    /** 风险 1：同名 unit 由系统提供时必须退出 2，且一个字节都不写。 */
    @Test
    void refusesShadowedSystemUnitWithExitTwo() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = runnerShowing(
                "LoadState=loaded\nFragmentPath=/usr/lib/systemd/system/ssh.service\n");

        assertEquals(2, apply(false, runner));

        assertEquals(1, runner.calls().size(), runner.calls().toString());
        assertEquals(Arrays.asList("systemctl", "show", "xac.demo.service", SHOW_PROPERTY),
                runner.calls().get(0));
        assertFalse(Files.exists(unitPath()));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target().unitDir)) {
            assertFalse(entries.iterator().hasNext(), "预检失败不应写入任何 unit 文件");
        }
    }

    @Test
    void dryRunAlsoRefusesShadowedSystemUnit() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = runnerShowing(
                "LoadState=loaded\nFragmentPath=/usr/lib/systemd/system/ssh.service\n");

        assertEquals(2, apply(true, runner));

        assertEquals(1, runner.calls().size(), runner.calls().toString());
        assertFalse(Files.exists(unitPath()));
    }

    /** 风险 1 的反面：FragmentPath 正是本次要写的路径，说明是 xac 自己装过的，重复 apply。 */
    @Test
    void proceedsWhenFragmentPathIsTheTargetUnit() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = runnerShowing(
                "LoadState=loaded\nFragmentPath=" + unitPath() + "\n");

        assertEquals(0, apply(false, runner));

        assertTrue(Files.isRegularFile(unitPath()));
    }

    @Test
    void proceedsWhenUnitIsNotFound() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = runnerShowing("LoadState=not-found\nFragmentPath=\n");

        assertEquals(0, apply(false, runner));

        assertTrue(Files.isRegularFile(unitPath()));
    }

    /** 查询失败不能被当成「没有同名 unit」。 */
    @Test
    void refusesWhenSystemctlShowFails() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl",
                new ExecResult(1, "", "Failed to connect to bus"));

        assertEquals(2, apply(false, runner));

        assertEquals(1, runner.calls().size(), runner.calls().toString());
        assertFalse(Files.exists(unitPath()));
    }

    /** 红线：dry-run 每个动作一行，前缀 would，共 7 步。 */
    @Test
    void dryRunPrintsWouldPrefixForEveryStep() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        Captured captured = capture(true, runnerShowing("LoadState=not-found\nFragmentPath=\n"));

        assertEquals(0, captured.code);
        List<String> lines = Arrays.asList(captured.output.split("\n"));
        assertEquals(7, lines.size(), captured.output);
        for (String line : lines) {
            assertTrue(line.startsWith("would "), captured.output);
        }
        assertTrue(lines.get(0).contains("path-links"), captured.output);
        assertTrue(lines.get(6).contains("restart"), captured.output);
    }

    /** 红线：失败时先打印已完成步骤，再打印 failed: 与原因，退出 1。 */
    @Test
    void failurePrintsStepsThenFailedLineAndExitsOne() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        createTargetDirs();
        RecordingRunner runner = runnerShowing("LoadState=not-found\nFragmentPath=\n");
        runner.addResult(Arrays.asList("systemctl", "daemon-reload"),
                new ExecResult(1, "", "boom"));

        Captured captured = capture(false, runner);

        assertEquals(1, captured.code);
        List<String> lines = Arrays.asList(captured.output.split("\n"));
        assertEquals(6, lines.size(), captured.output);
        assertFalse(lines.get(0).startsWith("would "), captured.output);
        assertTrue(lines.get(4).contains("daemon-reload"), captured.output);
        assertTrue(lines.get(5).startsWith("failed: "), captured.output);
        assertTrue(lines.get(5).contains("boom"), captured.output);
    }
}
