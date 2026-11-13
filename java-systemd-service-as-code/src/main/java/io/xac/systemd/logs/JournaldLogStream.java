package io.xac.systemd.logs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.UnitNaming;

/**
 * journald 日志来源：{@code journalctl -u <unit> --no-pager -n N}，follow 时追加
 * {@code --follow}。
 *
 * <p>stdout 与 stderr 各由一个 daemon 泵线程按行读，避免任一管道写满导致子进程阻塞。
 * 非 follow 模式等待子进程退出后返回：exit 非 0 时抛 {@link LogSourceException}，
 * 消息是 stderr 原文，由 CLI 去掉尾部空白后输出（与旧 {@code ExecResult.stderr.trim()} 一致）。
 *
 * <p>{@link #close()} 先销毁子进程再 join 泵线程；幂等，且允许从泵线程自身调用（不会自锁）。
 * follow 模式注册 shutdown hook，保证 Ctrl-C 或正常退出时也走同一条清理路径。
 */
public final class JournaldLogStream implements LogStream {

    private static final long JOIN_TIMEOUT_MILLIS = 2000L;

    private final CommandRunner runner;
    private final ServiceSpec spec;
    private final boolean follow;
    private final Object lock = new Object();
    private final StringBuilder stderrText = new StringBuilder();

    private RunningCommand command;
    private Thread stdoutPump;
    private Thread stderrPump;
    private boolean started;
    private boolean closed;

    /** 便捷入口：默认不跟随，与 {@code ServicePackage.logs(int)} 的语义一致。 */
    public JournaldLogStream(CommandRunner runner, ServiceSpec spec) {
        this(runner, spec, false);
    }

    public JournaldLogStream(CommandRunner runner, ServiceSpec spec, boolean follow) {
        if (runner == null) {
            throw new NullPointerException("runner");
        }
        if (spec == null) {
            throw new NullPointerException("spec");
        }
        this.runner = runner;
        this.spec = spec;
        this.follow = follow;
    }

    @Override
    public void start(int lines, LogSink sink) {
        start(lines, sink, sink);
    }

    @Override
    public void start(int lines, final LogSink stdoutSink, final LogSink stderrSink) {
        if (lines <= 0) {
            throw new LogSourceException("-n must be a positive integer");
        }
        if (stdoutSink == null) {
            throw new NullPointerException("stdoutSink");
        }
        if (stderrSink == null) {
            throw new NullPointerException("stderrSink");
        }
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("log stream already started");
            }
            started = true;
        }
        List<String> argv = new ArrayList<String>(Arrays.asList(
                "journalctl", "-u", UnitNaming.unitName(spec), "--no-pager", "-n",
                String.valueOf(lines)));
        if (follow) {
            argv.add("--follow");
        }

        RunningCommand command;
        try {
            command = runner.start(argv);
        } catch (IllegalStateException e) {
            throw new LogSourceException(e.getMessage(), e);
        }

        synchronized (lock) {
            this.command = command;
            if (closed) {
                command.destroy();
            }
        }
        Thread hook = follow ? registerShutdownHook() : null;
        LogSink journalStderr = follow ? stderrSink : new LogSink() {
            @Override
            public void accept(String line) {
                stderrText.append(line).append('\n');
            }
        };
        Thread out = newPump("xac-journald-stdout", command, true, stdoutSink);
        Thread err = newPump("xac-journald-stderr", command, false, journalStderr);
        synchronized (lock) {
            stdoutPump = out;
            stderrPump = err;
        }
        out.start();
        err.start();
        try {
            if (follow) {
                join(out);
                join(err);
                try {
                    command.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                int exitCode = awaitExit(command);
                join(out);
                join(err);
                if (exitCode != 0 && !isClosed()) {
                    throw new LogSourceException(stderrText.toString());
                }
            }
        } finally {
            removeShutdownHook(hook);
        }
    }

    @Override
    public boolean isFollow() {
        return follow;
    }

    @Override
    public void close() {
        RunningCommand running;
        Thread out;
        Thread err;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            running = command;
            out = stdoutPump;
            err = stderrPump;
        }
        if (running == null) {
            join(out, JOIN_TIMEOUT_MILLIS);
            join(err, JOIN_TIMEOUT_MILLIS);
            return;
        }
        running.destroy();
        boolean outDone = join(out, JOIN_TIMEOUT_MILLIS);
        boolean errDone = join(err, JOIN_TIMEOUT_MILLIS);
        if (!outDone || !errDone) {
            running.destroyForcibly();
            join(out, JOIN_TIMEOUT_MILLIS);
            join(err, JOIN_TIMEOUT_MILLIS);
        }
    }

    private static int awaitExit(RunningCommand command) {
        try {
            return command.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogSourceException("interrupted: journalctl");
        }
    }

    private static Thread newPump(String name, final RunningCommand command,
            final boolean stdout, final LogSink sink) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(
                            stdout ? command.stdout() : command.stderr(),
                            StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sink.accept(line);
                    }
                } catch (IOException e) {
                    // 流被关闭或子进程异常终止：已读到的行已交给 sink，剩余内容不可得。
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                            // 关闭失败不会让已有输出失效。
                        }
                    }
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        for (;;) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 最多等待 {@code timeoutMillis}，返回线程是否已经退出。 */
    private static boolean join(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = false;
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private Thread registerShutdownHook() {
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                close();
            }
        }, "xac-journald-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return hook;
        } catch (IllegalStateException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static void removeShutdownHook(Thread hook) {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // JVM 已在关闭流程中，hook 会被执行或已执行。
        } catch (SecurityException e) {
            // 无权限移除；hook 调用幂等 close()，不会重复破坏状态。
        }
    }
}
