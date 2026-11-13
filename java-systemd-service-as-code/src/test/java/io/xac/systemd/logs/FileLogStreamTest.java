package io.xac.systemd.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLogStreamTest {

    @TempDir
    Path dir;

    private Path file(String content) throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void rejectsNonPositiveLineCount() throws IOException {
        FileLogStream stream = new FileLogStream(file("a\n"), 20L, true);

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> stream.start(0, new NoopSink()));

        assertEquals("-n must be a positive integer", failure.getMessage());
        stream.close();
    }

    @Test
    void reportsMissingFile() {
        Path missing = dir.resolve("missing.log");

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> new FileLogStream(missing, 20L, false));

        assertEquals("log file not found: " + missing, failure.getMessage());
    }

    @Test
    void reportsMissingFileWithConfiguredText() {
        Path missing = dir.resolve("missing.log");

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> new FileLogStream(missing, "/raw//missing.log", 20L, false));

        assertEquals("log file not found: /raw//missing.log", failure.getMessage());
    }

    @Test
    void defaultsToNonFollow() throws IOException {
        FileLogStream stream = new FileLogStream(file("a\n"), 20L);

        assertFalse(stream.isFollow());
        stream.close();
    }

    @Test
    void nonFollowReadsTailAndReturns() throws IOException {
        Path log = file("one\ntwo\nthree\n");
        final List<String> lines = new ArrayList<String>();
        FileLogStream stream = new FileLogStream(log, 20L, false);

        stream.start(2, new LogSink() {
            @Override
            public void accept(String line) {
                lines.add(line);
            }
        });

        assertEquals(Arrays.asList("two", "three"), lines);
        stream.close();
    }

    @Test
    void startIsSingleUse() throws IOException {
        FileLogStream stream = new FileLogStream(file("a\n"), 20L, false);
        stream.start(1, new NoopSink());

        assertThrows(IllegalStateException.class, () -> stream.start(1, new NoopSink()));
        stream.close();
    }

    @Test
    void streamsInitialTailThenAppends() throws Exception {
        Path log = file("one\ntwo\n");
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final FileLogStream stream = new FileLogStream(log, 20L, true);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.start(2, new LogSink() {
                    @Override
                    public void accept(String line) {
                        lines.add(line);
                    }
                });
            }
        });
        worker.start();

        waitFor(new Condition() {
            @Override
            public boolean holds() {
                return lines.size() >= 2;
            }
        });
        assertEquals(Arrays.asList("one", "two"), new ArrayList<String>(lines.subList(0, 2)));

        Files.write(log, "three\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        waitFor(new Condition() {
            @Override
            public boolean holds() {
                return lines.size() >= 3;
            }
        });
        assertEquals("three", lines.get(2));

        stream.close();
        worker.join(3000L);
        assertFalse(worker.isAlive(), "start() must return after close()");
        assertEquals(3, lines.size(), lines.toString());
    }

    @Test
    void flushPendingEmitsTrailingPartialLine() throws Exception {
        Path log = file("two\nthree");
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final FileLogStream stream = new FileLogStream(log, 20L, true);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.start(2, new LogSink() {
                    @Override
                    public void accept(String line) {
                        lines.add(line);
                    }
                });
            }
        });
        worker.start();
        waitFor(new Condition() {
            @Override
            public boolean holds() {
                return lines.size() >= 1;
            }
        });
        assertEquals(Arrays.asList("two"), new ArrayList<String>(lines));

        stream.close();
        worker.join(3000L);

        assertFalse(worker.isAlive(), "start() must return after close()");
        assertEquals(Arrays.asList("two", "three"), new ArrayList<String>(lines));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path log = file("one\n");
        final FileLogStream stream = new FileLogStream(log, 20L, true);
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.start(1, new LogSink() {
                    @Override
                    public void accept(String line) {
                        lines.add(line);
                    }
                });
            }
        });
        worker.start();
        waitFor(new Condition() {
            @Override
            public boolean holds() {
                return !lines.isEmpty();
            }
        });

        stream.close();
        stream.close();
        worker.join(3000L);

        assertFalse(worker.isAlive());
        assertEquals(Arrays.asList("one"), lines);
    }

    @Test
    void keepsUtf8LinesIntactAcrossReads() throws Exception {
        Path log = file("\u4e2d\u6587\n");
        final List<String> lines = new ArrayList<String>();
        FileLogStream stream = new FileLogStream(log, 20L, false);

        stream.start(1, new LogSink() {
            @Override
            public void accept(String line) {
                lines.add(line);
            }
        });

        assertEquals(Arrays.asList("\u4e2d\u6587"), lines);
        stream.close();
    }

    private static void waitFor(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("condition not reached in time");
    }

    private interface Condition {
        boolean holds();
    }

    private static final class NoopSink implements LogSink {
        @Override
        public void accept(String line) {
        }
    }
}
