package io.xac.systemd.exec;

/**
 * 测试夹具：向 stderr 写约 300KB，再向 stdout 打印 "done" 并正常退出（exit 0）。
 *
 * <p>用于复现顺序排空导致的管道死锁：stderr 管道缓冲区（Windows 约 4KB、Linux 约 64KB）
 * 被写满后子进程阻塞在写端，若父进程此时正阻塞在“读到 stdout EOF”，双方互等。</p>
 *
 * <p>不属于生产代码，仅供测试使用。</p>
 */
public final class StderrFlood {

    private static final int PAYLOAD_LINES = 60;
    private static final int LINE_LENGTH = 5120;

    private StderrFlood() {
    }

    public static void main(String[] args) {
        StringBuilder builder = new StringBuilder(LINE_LENGTH);
        for (int i = 0; i < LINE_LENGTH; i++) {
            builder.append((char) ('a' + (i % 26)));
        }
        String payload = builder.toString();
        for (int i = 0; i < PAYLOAD_LINES; i++) {
            System.err.println(payload);
        }
        System.err.flush();
        System.out.println("done");
        System.out.flush();
    }
}
