package io.xac.systemd.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void runsCommandAndCapturesStderr() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(ExecFixtures.javaExe(), "-version"));
        assertTrue(result.ok(), "exit=" + result.exitCode);
        assertTrue(result.stderr.contains("version"), result.stderr);
    }

    @Test
    void reportsNonZeroExitCode() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(ExecFixtures.javaExe(), "-XX:+NoSuchFlagForXac"));
        assertFalse(result.ok());
        assertTrue(result.exitCode > 0);
    }

    @Test
    void recordingRunnerRecordsArgvAndCannedResults() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, "", ""));
        runner.run(Arrays.asList("systemctl", "daemon-reload"));
        assertEquals(1, runner.calls().size());
        assertEquals(Arrays.asList("systemctl", "daemon-reload"), runner.calls().get(0));
    }

    @Test
    void recordingRunnerMatchesExactArgvBeforeExecutable() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, "generic", ""));
        runner.addResult(Arrays.asList("systemctl", "is-active", "demo"), new ExecResult(0, "active", ""));
        ExecResult exact = runner.run(Arrays.asList("systemctl", "is-active", "demo"));
        ExecResult other = runner.run(Arrays.asList("systemctl", "start", "demo"));
        assertEquals("active", exact.stdout);
        assertEquals("generic", other.stdout);
        assertEquals(2, runner.calls().size());
    }

    /**
     * 回归测试：子进程向 stderr 写约 300KB（远超管道缓冲区），随后才关闭 stdout。
     * 顺序排空的旧实现会在此死锁（子进程阻塞在写 stderr，父进程阻塞在读到 stdout EOF）。
     */
    @Test
    void drainsLargeStderrWithoutDeadlock() {
        final CommandRunner runner = new ProcessRunner();
        final List<String> argv = ExecFixtures.java(StderrFlood.class.getName());
        ThrowingSupplier<ExecResult> call = () -> runner.run(argv);
        ExecResult result = assertTimeoutPreemptively(Duration.ofSeconds(20), call);
        assertEquals(0, result.exitCode, result.stderr);
        assertTrue(result.stdout.contains("done"), result.stdout);
        assertTrue(result.stderr.length() >= 300_000, "stderr bytes=" + result.stderr.length());
    }
}
