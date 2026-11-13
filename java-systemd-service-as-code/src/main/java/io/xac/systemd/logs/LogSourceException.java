package io.xac.systemd.logs;

/**
 * 日志来源不可用：启动日志命令失败，或读取日志文件失败。
 *
 * <p>消息是面向用户的英文文案，CLI 原样（去尾部空白后）输出并以 2 退出。
 */
public final class LogSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LogSourceException(String message) {
        super(message);
    }

    public LogSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
