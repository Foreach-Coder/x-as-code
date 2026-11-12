package io.xac.systemd.logs;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsRunnerTest {

    @TempDir
    Path dir;

    private ServiceSpec spec() {
        return ServiceSpec.builder(Paths.get("/opt/demo"))
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private ServiceSpec fileSpec(Path logFile) {
        return ServiceSpec.builder(Paths.get("/opt/demo"))
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .logFile(logFile.toString())
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private Path logFile(String content) throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void readsConfiguredLogFileInsteadOfJournal() throws IOException {
        Path log = logFile("one\ntwo\nthree\n");
        RecordingRunner runner = new RecordingRunner();

        ExecResult result = new LogsRunner().run(fileSpec(log), runner, 2, false);

        assertEquals(0, result.exitCode);
        assertEquals("two\nthree\n", result.stdout);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
        assertTrue(runner.inheritIoCalls().isEmpty(), runner.inheritIoCalls().toString());
    }

    @Test
    void reportsMissingLogFileAsExitCodeTwo() {
        Path log = dir.resolve("missing.log");
        RecordingRunner runner = new RecordingRunner();

        ExecResult result = new LogsRunner().run(fileSpec(log), runner, 10, false);

        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.startsWith("log file not found: "), result.stderr);
        assertTrue(result.stderr.contains("missing.log"), result.stderr);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void rejectsNonPositiveLineCountBeforeTouchingLogFile() {
        Path log = dir.resolve("missing.log");
        RecordingRunner runner = new RecordingRunner();

        ExecResult result = new LogsRunner().run(fileSpec(log), runner, 0, false);

        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.contains("-n must be a positive integer"), result.stderr);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void readsJournalWithNoPager() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("journalctl", new ExecResult(0, "line1\nline2\n", ""));

        ExecResult result = new LogsRunner().run(spec(), runner, 100, false);

        assertEquals("line1\nline2\n", result.stdout);
        assertEquals(Arrays.asList("journalctl", "-u", "xac.demo.service", "--no-pager", "-n", "100"),
                runner.calls().get(0));
        assertTrue(runner.inheritIoCalls().isEmpty());
    }

    @Test
    void followsWithInheritIo() {
        RecordingRunner runner = new RecordingRunner();

        ExecResult result = new LogsRunner().run(spec(), runner, 50, true);

        assertEquals(0, result.exitCode);
        assertEquals(Arrays.asList("journalctl", "-u", "xac.demo.service", "--no-pager", "-n", "50",
                        "--follow"),
                runner.inheritIoCalls().get(0));
        assertTrue(runner.calls().isEmpty());
    }

    @Test
    void turnsInheritIoFailureIntoExitCodeTwo() {
        CommandRunner failing = new CommandRunner() {
            @Override
            public ExecResult run(List<String> argv) {
                return new ExecResult(0, "", "");
            }

            @Override
            public void runInheritIo(List<String> argv) {
                throw new IllegalStateException("无法执行 journalctl: boom");
            }

            @Override
            public RunningCommand start(List<String> argv) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };

        ExecResult result = new LogsRunner().run(spec(), failing, 10, true);

        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.contains("无法执行 journalctl"), result.stderr);
    }

    @Test
    void rejectsNonPositiveLineCount() {
        RecordingRunner runner = new RecordingRunner();

        ExecResult result = new LogsRunner().run(spec(), runner, 0, false);

        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.contains("-n"), result.stderr);
        assertTrue(runner.calls().isEmpty());
    }
}
