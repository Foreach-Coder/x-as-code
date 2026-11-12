package io.xac.systemd.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTailTest {

    @TempDir
    Path dir;

    private Path file(String content) throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String last(Path file, int lines) throws IOException {
        return FileTail.readLastLines(file, lines);
    }

    private static String text(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void returnsLastLines() throws IOException {
        Path f = file("a\nb\nc\nd\ne\n");
        assertEquals("d\ne\n", last(f, 2));
    }

    @Test
    void returnsWholeFileWhenFewerLinesExist() throws IOException {
        Path f = file("a\nb\n");
        assertEquals("a\nb\n", last(f, 10));
    }

    @Test
    void handlesFileWithoutTrailingNewline() throws IOException {
        Path f = file("a\nb\nc");
        assertEquals("b\nc", last(f, 2));
    }

    @Test
    void returnsSingleLastLineWithoutTrailingNewline() throws IOException {
        Path f = file("a\nb\nc");
        assertEquals("c", last(f, 1));
    }

    @Test
    void returnsEmptyStringForEmptyFile() throws IOException {
        Path f = file("");
        assertEquals("", last(f, 5));
    }

    @Test
    void keepsUtf8MultiByteLines() throws IOException {
        Path f = file("one\n\u4e2d\u6587\n");
        assertEquals("\u4e2d\u6587\n", last(f, 1));
    }

    @Test
    void followPrintsInitialTailThenAppendedBytes() throws Exception {
        Path f = file("start\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileTail tail = new FileTail(f, out, 20L);
        Thread worker = new Thread(() -> {
            try {
                tail.follow(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();

        waitFor(() -> text(out).contains("start\n"));
        append(f, "next\n");
        waitFor(() -> text(out).contains("start\nnext\n"));

        tail.requestStop();
        worker.join(2000L);
        assertEquals("start\nnext\n", text(out));
    }

    @Test
    void followRestartsFromBeginningAfterTruncation() throws Exception {
        Path f = file("before\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileTail tail = new FileTail(f, out, 20L);
        Thread worker = new Thread(() -> {
            try {
                tail.follow(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();

        waitFor(() -> text(out).contains("before\n"));
        Files.write(f, "after\n".getBytes(StandardCharsets.UTF_8));
        waitFor(() -> text(out).contains("after\n"));

        tail.requestStop();
        worker.join(2000L);
        assertTrue(text(out).endsWith("after\n"), text(out));
    }

    private static void append(Path file, String value) throws IOException {
        Files.write(file, value.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
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
}
