package io.xac.systemd.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProcessRunner implements CommandRunner {

    @Override
    public ExecResult run(List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ExecResult(127, "", "无法执行 " + argv.get(0) + ": " + e.getMessage());
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutDrain = startDrain("xac-stdout-drain", process.getInputStream(), stdout);
        Thread stderrDrain = startDrain("xac-stderr-drain", process.getErrorStream(), stderr);

        try {
            int exitCode = process.waitFor();
            stdoutDrain.join();
            stderrDrain.join();
            return new ExecResult(exitCode, utf8(stdout), utf8(stderr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(130, "", "被中断: " + argv.get(0));
        }
    }

    @Override
    public void runInheritIo(List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.inheritIO();
        try {
            builder.start().waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 " + argv.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动一个后台线程把流读到 EOF。stdout 与 stderr 必须并发排空，否则当子进程
     * 写满 stderr 管道（缓冲区通常仅 4–64KB）时会阻塞在写端，而父进程仍阻塞在
     * stdout 读到 EOF 上，形成死锁。
     */
    private static Thread startDrain(String name, final InputStream in, final ByteArrayOutputStream sink) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[8192];
                try {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    // 流被关闭或子进程异常终止：保留已读到的内容。
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String utf8(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
