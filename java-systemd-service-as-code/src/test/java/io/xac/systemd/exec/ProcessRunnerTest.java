package io.xac.systemd.exec;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
