package io.xac.systemd.tui;

import java.io.IOException;

/**
 * 按键来源。TuiApp 只依赖这个接口，测试用脚本按键，真实终端用 Lanterna。
 */
public interface KeySource {

    /**
     * 取一个按键；最多等待 {@code timeoutMillis} 毫秒，超时返回 null。
     */
    KeyEvent next(long timeoutMillis) throws IOException;
}
