package io.xac.systemd.exec;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunningCommandTest {

    @Test
    void streamsStdoutAndWaitsForExit() {
        List<String> lines = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            RunningCommand command = new ProcessRunner()
                    .start(ExecFixtures.java(ExecFixtures.LinePrinter.class.getName()));
            List<String> read = readLines(command.stdout(), 3);
            assertEquals(0, command.waitFor());
            assertFalse(command.isAlive());
            return read;
        });
        assertEquals(Arrays.asList("A", "B", "C"), lines);
    }

    @Test
    void destroyTerminatesProcess() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            RunningCommand command = new ProcessRunner()
                    .start(ExecFixtures.java(ExecFixtures.Waiter.class.getName()));
            int first = command.stdout().read();
            assertEquals('R', first);
            assertTrue(command.isAlive());
            command.destroy();
            command.waitFor();
            assertFalse(command.isAlive());
            return null;
        });
    }

    @Test
    void recordingRunnerScriptsStartedCommands() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            RecordingRunner runner = new RecordingRunner();
            List<String> argv = Arrays.asList("journalctl", "-u", "xac.demo.service", "--follow");
            runner.addStartedOutput(argv, "A\nB\nC\n", "", false);

            RunningCommand command = runner.start(argv);

            assertEquals(Arrays.asList("A", "B", "C"), readLines(command.stdout(), 3));
            assertTrue(command.isAlive());
            command.destroy();
            assertEquals(0, command.waitFor());
            assertFalse(command.isAlive());
            assertEquals(1, runner.startedCalls().size());
            assertEquals(argv, runner.startedCalls().get(0));
            return null;
        });
    }

    private static List<String> readLines(InputStream in, int count) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            lines.add(line);
        }
        return lines;
    }
}
