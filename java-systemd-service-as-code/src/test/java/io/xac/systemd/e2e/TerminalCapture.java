package io.xac.systemd.e2e;

/**
 * Minimal VT100 screen replayer for the raw PTY stream captured by {@code script}.
 *
 * <p>Lanterna emits absolute cursor moves and "cursor forward" runs instead of literal
 * spaces, so asserting on the raw stream cannot find ordinary sentences. This helper
 * applies the subset of escape sequences the TUI actually produces and returns the last
 * frame as plain text.</p>
 */
final class TerminalCapture {

    private TerminalCapture() {
    }

    static String render(String raw, int rows, int cols) {
        char[][] grid = new char[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                grid[y][x] = ' ';
            }
        }
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
                    case 'J':
                        if (number(numbers, 0, 0) == 2) {
                            for (int y = 0; y < rows; y++) {
                                for (int x = 0; x < cols; x++) {
                                    grid[y][x] = ' ';
                                }
                            }
                        }
                        break;
                    case 'K':
                        for (int x = Math.max(col, 0); x < cols; x++) {
                            grid[clamp(row, rows)][x] = ' ';
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
            if (row >= 0 && row < rows && col >= 0 && col < cols) {
                grid[row][col] = c;
            }
            col++;
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            sb.append(new String(grid[y]));
            if (y < rows - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isFinal(char c) {
        return c >= '@' && c <= '~';
    }

    private static int[] parse(String params) {
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

    private static int number(int[] numbers, int index, int fallback) {
        if (index >= numbers.length || numbers[index] <= 0) {
            return fallback;
        }
        return numbers[index];
    }

    private static int clamp(int row, int rows) {
        return Math.max(0, Math.min(rows - 1, row));
    }
}
