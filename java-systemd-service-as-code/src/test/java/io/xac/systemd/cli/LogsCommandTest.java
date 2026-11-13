package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsCommandTest {

    private static final String EOL = System.lineSeparator();

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
                        + EOL,
                errBuffer.toString("UTF-8"));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void journalctlPathUsesInjectedRunner() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(journaldArgv(10, false), "hello\n", "", false);

        int code = run(runner, 10, false, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("hello\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
        assertEquals(journaldArgv(10, false), runner.startedCalls().get(0));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void nonPositiveLineCountPrintsExactStderrAndExitsTwo() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();

        int code = run(runner, 0, false, outBuffer, errBuffer);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("-n must be a positive integer" + EOL, errBuffer.toString("UTF-8"));
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void missingLogFilePrintsExactStderrAndExitsTwo() throws Exception {
        Path missing = pkg.resolve("missing.log");
        String configured = missing.toString().replace("\\", "/");
        writeSpecWithLogFile(missing);
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();

        int code = run(runner, 10, false, outBuffer, errBuffer);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("log file not found: " + configured + EOL, errBuffer.toString("UTF-8"));
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void configuredFilePrintsExactTailAndExitsZero() throws Exception {
        Path log = pkg.resolve("app.log");
        Files.write(log, "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));
        writeSpecWithLogFile(log);
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();

        int code = run(runner, 2, false, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("two\nthree\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void configuredFileWithoutTrailingNewlineAddsOne() throws Exception {
        Path log = pkg.resolve("app.log");
        Files.write(log, "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8));
        writeSpecWithLogFile(log);
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();

        int code = run(runner, 2, false, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("two\nthree\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
    }

    @Test
    void journalFollowUsesFollowFlagAndExitsZero() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(journaldArgv(10, true), "line1\nline2\n", "", false);

        int code = run(runner, 10, true, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("line1\nline2\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
        assertEquals(journaldArgv(10, true), runner.startedCalls().get(0));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void journalFollowSendsStderrToErr() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(journaldArgv(10, true), "out\n", "warn\n", false);

        int code = run(runner, 10, true, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("out\n", outBuffer.toString("UTF-8"));
        assertEquals("warn\n", errBuffer.toString("UTF-8"));
        assertEquals(journaldArgv(10, true), runner.startedCalls().get(0));
    }

    @Test
    void journalNonFollowSuccessDiscardsStderr() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(journaldArgv(10, false), "out\n", "warn\n", false);

        int code = run(runner, 10, false, outBuffer, errBuffer);

        assertEquals(0, code);
        assertEquals("out\n", outBuffer.toString("UTF-8"));
        assertEquals("", errBuffer.toString("UTF-8"));
    }

    @Test
    void journalStartFailurePrintsMessageAndExitsTwo() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();

        int code = run(failingStart("cannot execute journalctl: no such file"),
                10, true, outBuffer, errBuffer);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("cannot execute journalctl: no such file" + EOL, errBuffer.toString("UTF-8"));
    }

    @Test
    void journalFailurePrintsStderrAndExitsTwo() throws Exception {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();

        int code = run(failingJournal("boom\n"), 10, false, outBuffer, errBuffer);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("boom" + EOL, errBuffer.toString("UTF-8"));
    }

    private int run(CommandRunner runner, int lines, boolean follow,
                    ByteArrayOutputStream outBuffer, ByteArrayOutputStream errBuffer)
            throws IOException {
        PrintStream out = new PrintStream(outBuffer, true, "UTF-8");
        PrintStream err = new PrintStream(errBuffer, true, "UTF-8");
        SystemdTarget target = new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
        return LogsCommand.run(pkg, runner, new NoRootProbe(), target, lines, follow, out, err);
    }

    private static List<String> journaldArgv(int lines, boolean follow) {
        List<String> argv = new ArrayList<String>(Arrays.asList(
                "journalctl", "-u", "xac.demo.service", "--no-pager", "-n", String.valueOf(lines)));
        if (follow) {
            argv.add("--follow");
        }
        return argv;
    }

    private static CommandRunner failingStart(final String message) {
        return new CommandRunner() {
            @Override
            public ExecResult run(List<String> command) {
                return new ExecResult(0, "", "");
            }

            @Override
            public void runInheritIo(List<String> command) {
            }

            @Override
            public RunningCommand start(List<String> command) {
                throw new IllegalStateException(message);
            }
        };
    }

    private static CommandRunner failingJournal(final String stderr) {
        return new CommandRunner() {
            @Override
            public ExecResult run(List<String> command) {
                return new ExecResult(0, "", "");
            }

            @Override
            public void runInheritIo(List<String> command) {
            }

            @Override
            public RunningCommand start(List<String> command) {
                return new RunningCommand() {
                    @Override
                    public InputStream stdout() {
                        return new ByteArrayInputStream(new byte[0]);
                    }

                    @Override
                    public InputStream stderr() {
                        return new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public int waitFor() {
                        return 1;
                    }

                    @Override
                    public void destroy() {
                    }

                    @Override
                    public boolean isAlive() {
                        return false;
                    }
                };
            }
        };
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

    private void writeSpecWithLogFile(Path log) throws IOException {
        Files.write(pkg.resolve("xac.yaml"), String.join("\n", Arrays.asList(
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "logs:",
                "  file: " + log.toString().replace("\\", "/"),
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
