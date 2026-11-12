package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsCommandTest {

    @TempDir
    Path pkg;

    @Test
    void missingXacYamlPrintsExactStderrAndExitsTwo() throws Exception {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();

        int code = run(runner, 10, false, outBuffer, errBuffer);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("xac.yaml: xac.yaml not found in package directory"
                        + System.lineSeparator(),
                errBuffer.toString("UTF-8"));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void journalctlPathUsesInjectedRunner() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("journalctl", new ExecResult(0, "hello\n", ""));

        int code = run(runner, 10, false, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("hello\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
        assertEquals(Arrays.asList("journalctl", "-u", "xac.demo.service",
                        "--no-pager", "-n", "10"),
                runner.calls().get(0));
    }

    private int run(RecordingRunner runner, int lines, boolean follow,
                    ByteArrayOutputStream outBuffer, ByteArrayOutputStream errBuffer)
            throws IOException {
        PrintStream out = new PrintStream(outBuffer, true, "UTF-8");
        PrintStream err = new PrintStream(errBuffer, true, "UTF-8");
        SystemdTarget target = new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
        return LogsCommand.run(pkg, runner, new NoRootProbe(), target, lines, follow, out, err);
    }

    private void writeSpec() throws IOException {
        Files.write(pkg.resolve("xac.yaml"), String.join("\n", Arrays.asList(
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "")).getBytes(StandardCharsets.UTF_8));
    }

    private static final class NoRootProbe implements EnvironmentProbe {

        @Override
        public boolean isRoot() {
            return false;
        }

        @Override
        public boolean systemdRunning() {
            return false;
        }

        @Override
        public boolean fileReadable(Path path) {
            return false;
        }

        @Override
        public boolean executable(Path path) {
            return false;
        }
    }
}
