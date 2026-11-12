package io.xac.systemd.exec;

/**
 * 测试夹具：向 stdout 打印一行（缺省 {@code READY}）并立即 flush，随后阻塞 60 秒。
 *
 * <p>父进程必须先读到首行，子进程才会继续存活；用于验证流式读取与
 * {@link RunningCommand#destroy()} 能终止一个仍在运行的子进程。</p>
 *
 * <p>不属于生产代码，仅供测试使用。</p>
 */
public final class PrintThenWait {

    private static final long HOLD_MILLIS = 60_000L;

    private PrintThenWait() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println(args.length == 0 ? "READY" : args[0]);
        System.out.flush();
        Thread.sleep(HOLD_MILLIS);
    }
}
