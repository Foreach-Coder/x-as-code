package io.xac.systemd.logs;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsRunnerTest {

    private ServiceSpec spec() {
        return ServiceSpec.builder(Paths.get("/opt/demo"))
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
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
