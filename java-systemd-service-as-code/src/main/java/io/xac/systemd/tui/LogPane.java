package io.xac.systemd.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 日志滚动缓冲：容量固定，只保留最近若干行；滚动位置与自动跟随解耦。
 *
 * <p>约定：{@code following} 为 true 时窗口贴住最后一行，{@code scrollBack} 必为 0；
 * 向上滚动会暂停跟随并冻结当前窗口，即使新行不断到达也不移动；
 * 回到底部（或按 G）恢复跟随。所有方法线程安全：日志读取线程调用
 * {@link #append(String)}，UI 线程读取 {@link #visible(int)}，不做跨线程重排。
 */
public final class LogPane {

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<String>();
    private int scrollBack;
    private boolean following = true;

    public LogPane(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 追加一行并保持跟随窗口；超出容量时丢最旧的行。
     * 暂停跟随时只入缓冲，窗口不动。
     */
    public synchronized void append(String line) {
        if (line == null) {
            return;
        }
        lines.addLast(line);
        if (!following) {
            // 暂停时窗口钉在原来的绝对位置：新行到达不把画面顶走。
            scrollBack++;
        }
        while (lines.size() > capacity) {
            lines.removeFirst();
            if (!following && scrollBack > 0) {
                scrollBack--;
            }
        }
        if (!following) {
            scrollBack = Math.min(scrollBack, lines.size());
        }
    }

    public synchronized boolean following() {
        return following;
    }

    public synchronized int size() {
        return lines.size();
    }

    /** 当前距离底部多少行；0 表示跟随。 */
    public synchronized int scrollBack() {
        return scrollBack;
    }

    /** 向上滚动若干行：立即暂停跟随，到达顶部后夹住。 */
    public synchronized void scrollUp(int rows) {
        if (rows <= 0) {
            return;
        }
        following = false;
        scrollBack = Math.min(scrollBack + rows, Math.max(0, lines.size() - 1));
    }

    /** 向下滚动若干行：回到底部时恢复跟随。 */
    public synchronized void scrollDown(int rows) {
        if (rows <= 0) {
            return;
        }
        scrollBack = Math.max(0, scrollBack - rows);
        if (scrollBack == 0) {
            following = true;
        }
    }

    /** 跳到最旧一行并暂停跟随。 */
    public synchronized void toTop() {
        following = false;
        scrollBack = Math.max(0, lines.size() - 1);
    }

    /** 跳到最新一行并恢复跟随。 */
    public synchronized void toBottom() {
        following = true;
        scrollBack = 0;
    }

    /** L 键：在跟随与暂停之间切换；恢复时立即贴底。 */
    public synchronized void toggleFollow() {
        if (following) {
            following = false;
        } else {
            toBottom();
        }
    }

    /**
     * 按当前跟随/滚动状态返回最多 {@code height} 行。
     * 行内容原样返回：宽度裁剪由 {@link Canvas#text(int, int, String)} 负责，
     * 这里绝不按 char 下标截断，避免把 CJK 宽字符切成半个。
     */
    public synchronized List<String> visible(int height) {
        return visible(height, following);
    }

    /** 显式指定是否跟随，便于测试与绘制同一帧内的多次取值。 */
    public synchronized List<String> visible(int height, boolean follow) {
        if (height <= 0 || lines.isEmpty()) {
            return new ArrayList<String>();
        }
        int count = Math.min(height, lines.size());
        int end = follow ? lines.size() : Math.max(0, lines.size() - scrollBack);
        int start = Math.max(0, end - count);
        int windowEnd = Math.min(lines.size(), start + count);
        List<String> window = new ArrayList<String>(count);
        int index = 0;
        for (String line : lines) {
            if (index >= start && index < windowEnd) {
                window.add(line);
            }
            if (index >= windowEnd) {
                break;
            }
            index++;
        }
        return window;
    }
}
