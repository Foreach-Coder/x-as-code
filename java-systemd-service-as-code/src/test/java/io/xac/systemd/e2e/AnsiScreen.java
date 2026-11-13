package io.xac.systemd.e2e;

import io.xac.systemd.tui.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays the raw PTY stream produced by {@code script} into a character grid with
 * semantic {@link Role}s.
 *
 * <p>Lanterna writes absolute cursor moves and "cursor forward" runs instead of literal
 * spaces, and paints role colors through SGR sequences. Comparing the raw stream against
 * expected text is therefore useless; this replayer applies the subset of VT100 the TUI
 * emits and exposes the last frame as both text and role grid.</p>
 *
 * <p>Color to role mapping mirrors {@code LanternaDisplay.style}: cyan accents, red
 * warnings, green success, bright-black dim text, white-on-blue selection.</p>
 */
final class AnsiScreen {

    private static final int FG_RED = 31;
    private static final int FG_GREEN = 32;
    private static final int FG_BLUE = 34;
    private static final int FG_CYAN = 36;
    private static final int FG_BLACK = 30;
    private static final int FG_RED_BRIGHT = 91;
    private static final int FG_GREEN_BRIGHT = 92;
    private static final int FG_BLUE_BRIGHT = 94;
    private static final int FG_CYAN_BRIGHT = 96;
    private static final int FG_BLACK_BRIGHT = 90;
    private static final int BG_BLUE = 44;
    private static final int BG_BLUE_BRIGHT = 104;

    private AnsiScreen() {
    }

    static Screen parse(String raw, int rows, int cols) {
        Cell[][] grid = new Cell[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                grid[y][x] = new Cell(' ', Role.DEFAULT);
            }
        }
        Style style = new Style();
        int row = 0;
        int col = 0;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\u001b' && i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < raw.length() && !isFinal(raw.charAt(j))) {
                    j++;
                }
                if (j >= raw.length()) {
                    break;
                }
                String params = raw.substring(i + 2, j);
                char finalByte = raw.charAt(j);
                int[] numbers = parse(params);
                switch (finalByte) {
                    case 'H':
                    case 'f':
                        row = number(numbers, 0, 1) - 1;
                        col = number(numbers, 1, 1) - 1;
                        break;
                    case 'A':
                        row -= number(numbers, 0, 1);
                        break;
                    case 'B':
                        row += number(numbers, 0, 1);
                        break;
                    case 'C':
                        col += number(numbers, 0, 1);
                        break;
                    case 'D':
                        col -= number(numbers, 0, 1);
                        break;
                    case 'G':
                        col = number(numbers, 0, 1) - 1;
                        break;
                    case 'd':
                        row = number(numbers, 0, 1) - 1;
                        break;
                    case 'm':
                        style.apply(numbers);
                        break;
                    case 'J':
                        if (number(numbers, 0, 0) == 2) {
                            for (int y = 0; y < rows; y++) {
                                for (int x = 0; x < cols; x++) {
                                    grid[y][x] = new Cell(' ', Role.DEFAULT);
                                }
                            }
                        }
                        break;
                    case 'K':
                        for (int x = Math.max(col, 0); x < cols; x++) {
                            grid[clamp(row, rows)][x] = new Cell(' ', Role.DEFAULT);
                        }
                        break;
                    default:
                        break;
                }
                i = j + 1;
                continue;
            }
            if (c == '\r') {
                col = 0;
                i++;
                continue;
            }
            if (c == '\n') {
                row++;
                col = 0;
                i++;
                continue;
            }
            if (c == '\u0007' || c == '\u0000') {
                i++;
                continue;
            }
            int codePoint = raw.codePointAt(i);
            int chars = Character.charCount(codePoint);
            int width = displayWidth(codePoint);
            if (row >= 0 && row < rows && col >= 0 && col < cols) {
                if (width == 2 && col + 1 < cols) {
                    // 宽字形占两列：第二列在终端里由字形自身覆盖，网格留空格。
                    grid[row][col] = new Cell((char) codePoint, style.role());
                    grid[row][col + 1] = new Cell(' ', style.role());
                } else {
                    grid[row][col] = new Cell((char) codePoint, style.role());
                }
            }
            col += width;
            i += chars;
        }
        return new Screen(grid, rows, cols);
    }

    static final class Screen {

        private final Cell[][] grid;
        private final int rows;
        private final int cols;

        Screen(Cell[][] grid, int rows, int cols) {
            this.grid = grid;
            this.rows = rows;
            this.cols = cols;
        }

        int rows() {
            return rows;
        }

        int cols() {
            return cols;
        }

        char charAt(int x, int y) {
            if (x < 0 || x >= cols || y < 0 || y >= rows) {
                return ' ';
            }
            return grid[y][x].ch;
        }

        Role roleAt(int x, int y) {
            if (x < 0 || x >= cols || y < 0 || y >= rows) {
                return Role.DEFAULT;
            }
            return grid[y][x].role;
        }

        String line(int y) {
            StringBuilder sb = new StringBuilder(cols);
            for (int x = 0; x < cols; x++) {
                sb.append(charAt(x, y));
            }
            return sb.toString();
        }

        List<String> lines() {
            List<String> out = new ArrayList<String>(rows);
            for (int y = 0; y < rows; y++) {
                out.add(line(y));
            }
            return out;
        }

        String text() {
            StringBuilder sb = new StringBuilder(rows * (cols + 1));
            for (int y = 0; y < rows; y++) {
                sb.append(line(y));
                if (y < rows - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        /** 任意格是给定角色就返回 true。 */
        boolean hasRole(Role role) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (grid[y][x].role == role) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 该行内出现的角色（去重后按首次出现顺序）。 */
        List<Role> rolesInRow(int y) {
            List<Role> out = new ArrayList<Role>(4);
            for (int x = 0; x < cols; x++) {
                Role role = roleAt(x, y);
                if (role != Role.DEFAULT && !out.contains(role)) {
                    out.add(role);
                }
            }
            return out;
        }
    }

    private static final class Cell {

        final char ch;
        final Role role;

        Cell(char ch, Role role) {
            this.ch = ch;
            this.role = role;
        }
    }

    /** 当前 SGR 状态。 */
    private static final class Style {

        int fg = -1;
        int bg = -1;
        boolean reverse;

        void apply(int[] numbers) {
            if (numbers.length == 0) {
                reset();
                return;
            }
            for (int number : numbers) {
                if (number == 0) {
                    reset();
                } else if (number == 7) {
                    reverse = true;
                } else if (number == 27) {
                    reverse = false;
                } else if (number == 39) {
                    fg = -1;
                } else if (number == 49) {
                    bg = -1;
                } else if (isForeground(number)) {
                    fg = number;
                } else if (isBackground(number)) {
                    bg = number;
                }
            }
        }

        void reset() {
            fg = -1;
            bg = -1;
            reverse = false;
        }

        Role role() {
            if (reverse || bg == BG_BLUE || bg == BG_BLUE_BRIGHT) {
                return Role.SELECTED;
            }
            if (fg == FG_RED || fg == FG_RED_BRIGHT) {
                return Role.WARN;
            }
            if (fg == FG_GREEN || fg == FG_GREEN_BRIGHT) {
                return Role.OK;
            }
            if (fg == FG_CYAN || fg == FG_CYAN_BRIGHT
                    || fg == FG_BLUE || fg == FG_BLUE_BRIGHT) {
                return Role.ACCENT;
            }
            if (fg == FG_BLACK || fg == FG_BLACK_BRIGHT) {
                return Role.DIM;
            }
            return Role.DEFAULT;
        }

        private static boolean isForeground(int number) {
            return (number >= 30 && number <= 37) || (number >= 90 && number <= 97);
        }

        private static boolean isBackground(int number) {
            return (number >= 40 && number <= 47) || (number >= 100 && number <= 107);
        }
    }

    static boolean isFinal(char c) {
        return c >= '@' && c <= '~';
    }

    static int[] parse(String params) {
        if (params.isEmpty()) {
            return new int[0];
        }
        String[] parts = params.split(";");
        int[] out = new int[parts.length];
        for (int k = 0; k < parts.length; k++) {
            try {
                out[k] = Integer.parseInt(parts[k]);
            } catch (NumberFormatException e) {
                out[k] = 0;
            }
        }
        return out;
    }

    static int number(int[] numbers, int index, int fallback) {
        if (index >= numbers.length || numbers[index] <= 0) {
            return fallback;
        }
        return numbers[index];
    }

    private static int clamp(int row, int rows) {
        return Math.max(0, Math.min(rows - 1, row));
    }

    private static int displayWidth(int codePoint) {
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }
        if (codePoint >= 0x1100 && codePoint <= 0x115F
                || codePoint >= 0x2E80 && codePoint <= 0xA4CF && codePoint != 0x303F
                || codePoint >= 0xAC00 && codePoint <= 0xD7A3
                || codePoint >= 0xF900 && codePoint <= 0xFAFF
                || codePoint >= 0xFE30 && codePoint <= 0xFE6F
                || codePoint >= 0xFF00 && codePoint <= 0xFF60
                || codePoint >= 0xFFE0 && codePoint <= 0xFFE6) {
            return 2;
        }
        return 1;
    }
}
