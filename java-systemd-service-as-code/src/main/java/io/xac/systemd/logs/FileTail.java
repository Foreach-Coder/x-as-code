package io.xac.systemd.logs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 按行读文件尾部，并可选地跟随追加内容。纯 JDK 实现，不调用外部 {@code tail}。
 *
 * <p>只跟随路径：文件变短视为被截断、从头重读；文件被改名换新（logback 轮转）不处理，
 * 这是 v1 的已知限制。
 */
public final class FileTail {

    private static final int CHUNK = 8192;

    private final Path file;
    private final OutputStream out;
    private final long pollMillis;
    private volatile boolean stopped;

    public FileTail(Path file, OutputStream out, long pollMillis) {
        this.file = file;
        this.out = out;
        this.pollMillis = pollMillis;
    }

    public void requestStop() {
        this.stopped = true;
    }

    /** 先打印最后 {@code initialLines} 行，然后持续输出新追加的字节。 */
    public void follow(int initialLines) throws IOException, InterruptedException {
        write(readLastLines(file, Math.max(initialLines, 1)));
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        try {
            long position = raf.length();
            byte[] chunk = new byte[CHUNK];
            while (!stopped) {
                long length = raf.length();
                if (length < position) {
                    position = 0L;
                }
                if (length > position) {
                    raf.seek(position);
                    long remaining = length - position;
                    while (remaining > 0) {
                        int n = (int) Math.min((long) CHUNK, remaining);
                        raf.readFully(chunk, 0, n);
                        out.write(chunk, 0, n);
                        remaining -= n;
                    }
                    position = raf.getFilePointer();
                    out.flush();
                }
                Thread.sleep(pollMillis);
            }
        } finally {
            raf.close();
        }
    }

    /**
     * 返回文件最后 {@code lines} 行（UTF-8）。从尾部按块向前找换行符，不把整个文件读进内存；
     * 找到起点后一次读出，避免按字节拼接破坏多字节字符。
     */
    public static String readLastLines(Path file, int lines) throws IOException {
        if (lines <= 0) {
            return "";
        }
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        try {
            long length = raf.length();
            if (length == 0L) {
                return "";
            }
            long start = findStart(raf, length, lines);
            byte[] data = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            raf.close();
        }
    }

    private static long findStart(RandomAccessFile raf, long length, int lines) throws IOException {
        raf.seek(length - 1);
        boolean trailingNewline = raf.read() == '\n';
        int needed = trailingNewline ? lines + 1 : lines;
        byte[] chunk = new byte[CHUNK];
        long position = length;
        int found = 0;
        while (position > 0) {
            int n = (int) Math.min((long) CHUNK, position);
            position -= n;
            raf.seek(position);
            raf.readFully(chunk, 0, n);
            for (int i = n - 1; i >= 0; i--) {
                if (chunk[i] == '\n') {
                    found++;
                    if (found == needed) {
                        return position + i + 1;
                    }
                }
            }
        }
        return 0L;
    }

    private void write(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
