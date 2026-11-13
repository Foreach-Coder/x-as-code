package io.xac.systemd.e2e;

/**
 * Minimal VT100 screen replayer for the raw PTY stream captured by {@code script}.
 *
 * <p>Lanterna emits absolute cursor moves and "cursor forward" runs instead of literal
 * spaces, so asserting on the raw stream cannot find ordinary sentences. {@link AnsiScreen}
 * owns the replay logic (it also tracks role colors); this class keeps the older
 * text-only entry point for the assertions that only need characters.</p>
 */
final class TerminalCapture {

    private TerminalCapture() {
    }

    static String render(String raw, int rows, int cols) {
        return AnsiScreen.parse(raw, rows, cols).text();
    }
}
