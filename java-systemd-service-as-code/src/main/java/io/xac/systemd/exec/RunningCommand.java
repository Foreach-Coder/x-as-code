package io.xac.systemd.exec;

import java.io.InputStream;

/**
 * 一条已启动、仍在运行的子进程。
 *
 * <p>与 {@link ExecResult} 的“跑完再取输出”不同，这里把子进程的 stdout/stderr
 * 直接交出去，调用方可以边读边处理（例如 {@code logs --follow}）。调用方负责排空
 * 关心的流；{@link #waitFor()}、{@link #destroy()}、{@link #isAlive()} 与
 * {@link Process} 语义一致。</p>
 */
public interface RunningCommand {

    /** 子进程的 stdout 管道；读到 EOF 表示子进程关闭了 stdout。 */
    InputStream stdout();

    /** 子进程的 stderr 管道。 */
    InputStream stderr();

    /** 等待子进程结束并返回退出码；当前线程被中断时抛 {@link InterruptedException}。 */
    int waitFor() throws InterruptedException;

    /** 请求结束子进程（{@link Process#destroy()} 语义）。 */
    void destroy();

    /** 强制结束子进程（{@link Process#destroyForcibly()} 语义）；默认退化为 {@link #destroy()}。 */
    default void destroyForcibly() {
        destroy();
    }

    /** 子进程是否仍在运行。 */
    boolean isAlive();
}
