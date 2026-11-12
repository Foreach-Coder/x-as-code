package io.xac.systemd.logs;

import io.xac.systemd.core.LogSourceException;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournaldLogStreamTest {

    private static ServiceSpec spec() {
        return new ServiceSpec.Builder(Paths.get("/opt/demo"))
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .build();
    }

    private static List<String> journalArgv(int lines, boolean follow) {
        List<String> argv = new ArrayList<String>(Arrays.asList(
                "journalctl", "-u", "xac.demo.service", "--no-pager", "-n", String.valueOf(lines)));
        if (follow) {
            argv.add("--follow");
        }
        return argv;
    }

    @Test
    void followPassesFollowFlagAndStreamsLines() {
        ServiceSpec spec = spec();
        List<String> argv = journalArgv(10, true);
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(argv, "one\ntwo\n", "", false);
        final List<String> lines = new ArrayList<String>();
        JournaldLogStream stream = new JournaldLogStream(runner, spec, true);

        stream.start(10, new LogSink() {
            @Override
            public void accept(String line) {
                lines.add(line);
            }
        });

        assertEquals(argv, runner.startedCalls().get(0));
        assertEquals(Arrays.asList("one", "two"), lines);
        stream.close();
    }

    @Test
    void nonFollowOmitsFollowFlag() {
        ServiceSpec spec = spec();
        List<String> argv = journalArgv(5, false);
        RecordingRunner runner = new RecordingRunner();
        runner.addStartedOutput(argv, "hello\n", "", false);
        final List<String> lines = new ArrayList<String>();
        JournaldLogStream stream = new JournaldLogStream(runner, spec, false);

        stream.start(5, new LogSink() {
            @Override
            public void accept(String line) {
                lines.add(line);
            }
        });

        assertEquals(argv, runner.startedCalls().get(0));
        assertEquals(Arrays.asList("hello"), lines);
        stream.close();
    }

    @Test
    void nonFollowRaisesStderrOnFailure() {
        ServiceSpec spec = spec();
        final List<String> argv = journalArgv(3, false);
        CommandRunner runner = new CommandRunner() {
            @Override
            public ExecResult run(List<String> command) {
                return new ExecResult(0, "", "");
            }

            @Override
            public void runInheritIo(List<String> command) {
            }

            @Override
            public RunningCommand start(List<String> command) {
                assertEquals(argv, command);
                return new RunningCommand() {
                    @Override
                    public InputStream stdout() {
                        return new ByteArrayInputStream(new byte[0]);
                    }

                    @Override
                    public InputStream stderr() {
                        return new ByteArrayInputStream("boom\n".getBytes(StandardCharsets.UTF_8));
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
        final JournaldLogStream stream = new JournaldLogStream(runner, spec, false);

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> stream.start(3, new NoopSink()));

        assertEquals("boom\n", failure.getMessage());
        stream.close();
    }

    @Test
    void wrapsStartFailure() {
        ServiceSpec spec = spec();
        CommandRunner runner = new CommandRunner() {
            @Override
            public ExecResult run(List<String> command) {
                return new ExecResult(0, "", "");
            }

            @Override
            public void runInheritIo(List<String> command) {
            }

            @Override
            public RunningCommand start(List<String> command) {
                throw new IllegalStateException("cannot execute journalctl: no such file");
            }
        };
        JournaldLogStream stream = new JournaldLogStream(runner, spec, true);

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> stream.start(10, new NoopSink()));

        assertEquals("cannot execute journalctl: no such file", failure.getMessage());
        stream.close();
    }

    @Test
    void closeDestroysProcessAndJoinsPumps() throws Exception {
        ServiceSpec spec = spec();
        List<String> argv = journalArgv(10, true);
        RecordingRunner recording = new RecordingRunner();
        recording.addStartedOutput(argv, "one\n", "warning\n", true);
        final CapturingRunner runner = new CapturingRunner(recording);
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final JournaldLogStream stream = new JournaldLogStream(runner, spec, true);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.start(10, new LogSink() {
                    @Override
                    public void accept(String line) {
                        lines.add(line);
                    }
                });
            }
        });
        worker.start();

        long deadline = System.currentTimeMillis() + 3000L;
        while (lines.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertEquals(Arrays.asList("one", "warning"), new ArrayList<String>(lines));
        assertTrue(runner.command.isAlive(), "process should still be alive while streaming");

        stream.close();
        worker.join(3000L);

        assertFalse(worker.isAlive(), "start() must return after close()");
        assertFalse(runner.command.isAlive(), "close() must destroy the child process");
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(thread.isAlive() && thread.getName().startsWith("xac-journald-"),
                    "residual thread: " + thread.getName());
        }
    }

    private static final class CapturingRunner implements CommandRunner {
        private final RecordingRunner delegate;
        private volatile RunningCommand command;

        private CapturingRunner(RecordingRunner delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecResult run(List<String> command) {
            return delegate.run(command);
        }

        @Override
        public void runInheritIo(List<String> command) {
            delegate.runInheritIo(command);
        }

        @Override
        public RunningCommand start(List<String> command) {
            RunningCommand started = delegate.start(command);
            this.command = started;
            return started;
        }
    }

    private static final class NoopSink implements LogSink {
        @Override
        public void accept(String line) {
        }
    }
}
