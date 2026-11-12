package io.xac.systemd.logs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.core.LogSourceException;

/**
 * 文件日志来源：非 follow 只读最后 N 行；follow 复用 {@link FileTail}（200ms 轮询、
 * 截断后从头重读），通过 {@link LineSinkOutputStream} 把字节流切成行交给 {@link LogSink}，
 * 不再直接写 {@code System.out}。
 *
 * <p>构造时就做「存在 + 可读」检查，保持旧 {@code LogsRunner} 的错误顺序与文案；
 * {@link #close()} 只请求 {@link FileTail} 停止，200ms 内返回。
 */
public final class FileLogStream implements LogStream {

    private final Path file;
    private final long pollMillis;
    private final boolean follow;
    private final Object lock = new Object();

    private FileTail tail;
    private boolean started;
    private boolean closed;

    /** 便捷入口：默认不跟随，与 {@code ServicePackage.logs(int)} 的语义一致。 */
    public FileLogStream(Path file, long pollMillis) {
        this(file, file == null ? null : file.toString(), pollMillis, false);
    }

    public FileLogStream(Path file, long pollMillis, boolean follow) {
        this(file, file == null ? null : file.toString(), pollMillis, follow);
    }

    /** {@code displayPath} 用于错误文案，保留 {@code xac.yaml} 里写的原始字符串。 */
    public FileLogStream(Path file, String displayPath, long pollMillis, boolean follow) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        String shown = displayPath == null ? file.toString() : displayPath;
        if (!Files.exists(file)) {
            throw new LogSourceException("log file not found: " + shown);
        }
        if (!Files.isReadable(file)) {
            throw new LogSourceException("log file is not readable: " + shown);
        }
        this.file = file;
        this.pollMillis = pollMillis;
        this.follow = follow;
    }

    @Override
    public void start(int lines, LogSink sink) {
        if (lines <= 0) {
            throw new LogSourceException("-n must be a positive integer");
        }
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("log stream already started");
            }
            started = true;
        }
        try {
            if (!follow) {
                emit(FileTail.readLastLines(file, lines), sink);
                return;
            }
            LineSinkOutputStream out = new LineSinkOutputStream(sink);
            FileTail started = new FileTail(file, out, pollMillis);
            synchronized (lock) {
                tail = started;
                if (closed) {
                    started.requestStop();
                }
            }
            try {
                started.follow(lines);
            } finally {
                out.flushPending();
            }
        } catch (IOException e) {
            throw new LogSourceException("cannot read log file: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isFollow() {
        return follow;
    }

    @Override
    public void close() {
        FileTail current;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            current = tail;
        }
        if (current != null) {
            current.requestStop();
        }
    }

    private static void emit(String text, LogSink sink) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                sink.accept(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            sink.accept(text.substring(start));
        }
    }

    /**
     * 把 {@link FileTail} 写出的字节流按 {@code \n} 切成行；{@code \r} 保留在行尾。
     * {@link #flush()} 不输出半行（否则轮询边界会把一行拆成多行），
     * {@link #flushPending()} 用于 follow 停止后补出最后一行没有换行符的残余。
     */
    private static final class LineSinkOutputStream extends OutputStream {

        private final LogSink sink;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private LineSinkOutputStream(LogSink sink) {
            this.sink = sink;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                emitLine();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int start = offset;
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (bytes[i] == '\n') {
                    buffer.write(bytes, start, i - start);
                    emitLine();
                    start = i + 1;
                }
            }
            if (start < end) {
                buffer.write(bytes, start, end - start);
            }
        }

        @Override
        public void flush() {
            // 行缓冲：半行留到换行符或 follow 结束后再发。
        }

        private void flushPending() {
            if (buffer.size() > 0) {
                emitLine();
            }
        }

        private void emitLine() {
            byte[] bytes = buffer.toByteArray();
            buffer.reset();
            sink.accept(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
