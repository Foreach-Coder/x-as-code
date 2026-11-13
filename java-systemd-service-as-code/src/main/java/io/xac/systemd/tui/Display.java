package io.xac.systemd.tui;

import java.io.Closeable;

/**
 * 画布宿主：持有终端尺寸并把一帧画布刷出去。TuiApp 只依赖这个抽象，
 * Lanterna 的 Terminal/Screen 只允许出现在 {@link LanternaDisplay} 里。
 */
public interface Display extends Closeable {

    void show(Canvas canvas);

    Size size();

    @Override
    void close();

    /** 终端尺寸（列 × 行）。 */
    final class Size {

        public final int width;
        public final int height;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
