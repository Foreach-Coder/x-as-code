package io.xac.systemd.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    private static String javaExe() {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        return java.toString();
    }

    @Test
    void runsCommandAndCapturesStderr() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(javaExe(), "-version"));
        assertTrue(result.ok(), "exit=" + result.exitCode);
        assertTrue(result.stderr.contains("version"), result.stderr);
    }

    @Test
    void reportsNonZeroExitCode() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(javaExe(), "-XX:+NoSuchFlagForXac"));
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
        final List<String> argv = Arrays.asList(javaExe(), "-cp", fixtureClasspath(),
                "io.xac.systemd.exec.StderrFlood");
        ThrowingSupplier<ExecResult> call = () -> runner.run(argv);
        ExecResult result = assertTimeoutPreemptively(Duration.ofSeconds(20), call);
        assertEquals(0, result.exitCode, result.stderr);
        assertTrue(result.stdout.contains("done"), result.stdout);
        assertTrue(result.stderr.length() >= 300_000, "stderr bytes=" + result.stderr.length());
    }

    /**
     * surefire 默认以 manifest-only booter jar 启动 fork，此时 {@code java.class.path}
     * 只剩 booter jar，子进程无法加载测试类；因此优先用测试类自身的 code source
     * （target/test-classes）并附带 java.class.path。
     */
    private static String fixtureClasspath() {
        String codeSource = null;
        try {
            java.net.URL location = ProcessRunnerTest.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equals(location.getProtocol())) {
                codeSource = Paths.get(location.toURI()).toString();
            }
        } catch (URISyntaxException e) {
            codeSource = null;
        }
        String javaClassPath = System.getProperty("java.class.path");
        if (codeSource == null) {
            return javaClassPath;
        }
        return codeSource + File.pathSeparator + javaClassPath;
    }
}
