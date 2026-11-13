package io.xac.systemd.logs;

/**
 * 日志行接收端。{@link LogStream} 只负责取行与切行，不直接写 {@code System.out}，
 * 由调用方决定输出位置与是否逐行刷新。
 */
public interface LogSink {

    /** 接收一行，不含行尾换行符（若原始日志是 {@code \r\n}，保留 {@code \r}）。 */
    void accept(String line);
}
