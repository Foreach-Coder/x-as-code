package io.xac.systemd.tui;

import java.util.Arrays;

/**
 * 定长字符画布：TUI 只往这里画，Display 负责把画布刷到终端。
 * 所有写入都按画布裁剪，越界不报错；只有读整行越界才抛异常（那是调用方的 bug）。
 */
public final class Canvas {

    private final int width;
    private final int height;
    private final char[][] cells;

    private int cursorX;
    private int cursorY;

    public Canvas(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        this.width = width;
        this.height = height;
        this.cells = new char[height][width];
        clear();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void clear() {
        for (int y = 0; y < height; y++) {
            Arrays.fill(cells[y], ' ');
        }
        cursorX = 0;
        cursorY = 0;
    }

    public void put(int x, int y, char c) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        cells[y][x] = c;
    }

    /**
     * 在 (x, y) 写一行文字。超宽截断、负数 x 从左侧裁剪、y 越界忽略，都不换行。
     */
    public void text(int x, int y, String value) {
        if (value == null || y < 0 || y >= height) {
            return;
        }
        int from = Math.max(0, x);
        int to = Math.min(width, x + value.length());
        for (int cx = from; cx < to; cx++) {
            cells[y][cx] = value.charAt(cx - x);
        }
    }

    /** 用字符填满第 y 行；y 越界忽略。 */
    public void hline(int y, char c) {
        if (y < 0 || y >= height) {
            return;
        }
        Arrays.fill(cells[y], c);
    }

    /**
     * 画一个边框盒（只画边，不填内部）。超出画布的部分被裁剪。
     */
    public void box(int x, int y, int boxWidth, int boxHeight) {
        if (boxWidth <= 0 || boxHeight <= 0) {
            return;
        }
        int right = x + boxWidth - 1;
        int bottom = y + boxHeight - 1;
        for (int cx = x; cx <= right; cx++) {
            put(cx, y, '─');
            put(cx, bottom, '─');
        }
        for (int cy = y; cy <= bottom; cy++) {
            put(x, cy, '│');
            put(right, cy, '│');
        }
        if (boxWidth >= 2 && boxHeight >= 2) {
            put(x, y, '┌');
            put(right, y, '┐');
            put(x, bottom, '└');
            put(right, bottom, '┘');
        }
    }

    public char charAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return ' ';
        }
        return cells[y][x];
    }

    /** 第 y 行的完整内容，长度恒为 {@link #width()}。 */
    public String line(int y) {
        if (y < 0 || y >= height) {
            throw new IllegalArgumentException("row out of range: " + y + " (height " + height + ")");
        }
        return new String(cells[y]);
    }

    public void setCursor(int x, int y) {
        this.cursorX = Math.max(0, Math.min(width - 1, x));
        this.cursorY = Math.max(0, Math.min(height - 1, y));
    }

    public int cursorX() {
        return cursorX;
    }

    public int cursorY() {
        return cursorY;
    }
}
