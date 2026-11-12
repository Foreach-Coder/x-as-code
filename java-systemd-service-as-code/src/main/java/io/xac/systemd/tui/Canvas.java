package io.xac.systemd.tui;

import java.util.Arrays;

/**
 * 定长字符画布：TUI 只往这里画，Display 负责把画布刷到终端。
 * 所有写入都按画布裁剪，越界不报错；只有读整行越界才抛异常（那是调用方的 bug）。
 *
 * <p>坐标按终端显示列计，不按 UTF-16 code unit 计：CJK/全角字符占两列，
 * 第一格放字符本身、第二格放 {@link #PLACEHOLDER}；增补平面字符（代理对）同样占两列，
 * 两格分别放高/低代理。渲染方必须跳过占位格，否则终端会把宽字形的第二列覆盖掉。
 */
public final class Canvas {

    /** 宽字符/代理对占用的第二格：只是列占位，渲染时必须跳过。 */
    public static final char PLACEHOLDER = '\0';

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

    /**
     * 在 (x, y) 写一个字符。宽字符会再占一格占位；越界、占位符与孤立代理忽略。
     */
    public void put(int x, int y, char c) {
        if (x < 0 || x >= width || y < 0 || y >= height
                || c == PLACEHOLDER || Character.isSurrogate(c)) {
            return;
        }
        eraseGlyphAt(y, x);
        cells[y][x] = c;
        if (displayWidth(c) == 2) {
            if (x + 1 >= width) {
                // 右边界只剩一格：不画半个宽字形。
                cells[y][x] = ' ';
                return;
            }
            eraseGlyphAt(y, x + 1);
            cells[y][x + 1] = PLACEHOLDER;
        }
    }

    /**
     * 在 (x, y) 写一行文字。超宽截断、负数 x 从左侧裁剪、y 越界忽略，都不换行。
     * 宽字符/代理对按两列推进，放不下整个字形时宁可不画。
     */
    public void text(int x, int y, String value) {
        if (value == null || y < 0 || y >= height) {
            return;
        }
        int col = x;
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int chars = Character.charCount(codePoint);
            int glyphWidth = displayWidth(codePoint);
            if (col >= width) {
                return;
            }
            if (col + glyphWidth > width) {
                // 宽字形在右边界放不下：整格丢弃，避免留下半个字形或孤立占位。
                return;
            }
            if (col + glyphWidth <= 0) {
                col += glyphWidth;
                i += chars;
                continue;
            }
            if (col < 0) {
                // 左边界外只露出宽字形的后半格：无法只画一半，清掉可见那一格。
                if (glyphWidth == 2) {
                    eraseGlyphAt(y, 0);
                    cells[y][0] = ' ';
                }
                col += glyphWidth;
                i += chars;
                continue;
            }
            eraseGlyphAt(y, col);
            if (glyphWidth == 2) {
                eraseGlyphAt(y, col + 1);
                if (chars == 2) {
                    cells[y][col] = value.charAt(i);
                    cells[y][col + 1] = value.charAt(i + 1);
                } else {
                    cells[y][col] = (char) codePoint;
                    cells[y][col + 1] = PLACEHOLDER;
                }
            } else if (chars == 1 && !Character.isSurrogate((char) codePoint)) {
                cells[y][col] = (char) codePoint;
            } else {
                // 宽度只有一列的代理码点无法用一格表达：用 '?' 占位，保证列数不变。
                cells[y][col] = '?';
            }
            col += glyphWidth;
            i += chars;
        }
    }

    /**
     * 用字符填满第 y 行；y 越界忽略。
     *
     * @throws IllegalArgumentException c 是宽字符或孤立代理时：逐格填充会让一个字形
     *         只占一格，破坏「宽字符占两列」的列宽不变量。
     */
    public void hline(int y, char c) {
        if (y < 0 || y >= height) {
            return;
        }
        if (displayWidth(c) == 2) {
            throw new IllegalArgumentException("hline does not accept double-width cells");
        }
        if (Character.isSurrogate(c)) {
            throw new IllegalArgumentException("hline does not accept isolated surrogates");
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

    /** 第 y 行的完整内容，长度恒为 {@link #width()}（含宽字符的占位格）。 */
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

    /**
     * 覆盖 (x, y) 前，把那里可能存在的宽字形/占位格成对清掉，
     * 避免留下半格宽字或孤立占位。
     */
    private void eraseGlyphAt(int y, int x) {
        if (x < 0 || x >= width) {
            return;
        }
        char current = cells[y][x];
        if (current == PLACEHOLDER) {
            cells[y][x] = ' ';
            if (x > 0) {
                cells[y][x - 1] = ' ';
            }
            return;
        }
        if (Character.isHighSurrogate(current) && x + 1 < width
                && Character.isLowSurrogate(cells[y][x + 1])) {
            cells[y][x] = ' ';
            cells[y][x + 1] = ' ';
            return;
        }
        if (Character.isLowSurrogate(current) && x > 0
                && Character.isHighSurrogate(cells[y][x - 1])) {
            // 覆盖代理对的后半格：前半格也必须一起清掉，否则留下孤立高代理。
            cells[y][x] = ' ';
            cells[y][x - 1] = ' ';
            return;
        }
        if (displayWidth(current) == 2 && x + 1 < width && cells[y][x + 1] == PLACEHOLDER) {
            cells[y][x] = ' ';
            cells[y][x + 1] = ' ';
        }
    }

    /**
     * 单个码点占用的终端列数。代理对固定占两列；BMP 里按东亚宽字符区间判断。
     */
    static int displayWidth(int codePoint) {
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }
        if (Character.isSurrogate((char) codePoint)) {
            return 1;
        }
        if (codePoint < 0x20 || (codePoint >= 0x7F && codePoint < 0xA0)) {
            return 1;
        }
        return isWideBmp((char) codePoint) ? 2 : 1;
    }

    private static boolean isWideBmp(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || c == 0x2329 || c == 0x232A
                || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE10 && c <= 0xFE19)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }
}
