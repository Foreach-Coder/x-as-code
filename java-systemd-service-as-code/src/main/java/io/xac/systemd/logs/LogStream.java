package io.xac.systemd.logs;

import io.xac.systemd.core.LogSourceException;

/**
 * 一个可消费的日志来源：journald 或日志文件。
 *
 * <p>{@link #start(int, LogSink)} 在非 follow 模式下读到来源结束（或失败）才返回；
 * follow 模式一直阻塞到来源结束或 {@link #close()}。{@link #close()} 必须幂等，
 * 并在返回前销毁子进程、等待接收线程退出，不留下后台线程。
 */
public interface LogStream extends AutoCloseable {

    /**
     * 读取最后 {@code lines} 行并交给 {@code sink}，随后按构造时的 follow 设置决定是否继续跟随。
     *
     * @throws LogSourceException 行数非正、来源不存在/不可读/无法启动或读取失败
     */
    void start(int lines, LogSink sink);

    @Override
    void close();
}
