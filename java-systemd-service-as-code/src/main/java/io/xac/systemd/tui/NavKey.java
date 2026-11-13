package io.xac.systemd.tui;

/**
 * TUI 认识的全部按键。字符键统一为 {@link #CHAR}，具体字符在 {@link KeyEvent#ch}。
 */
public enum NavKey {
    CHAR,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    PGUP,
    PGDN,
    TOP,
    BOTTOM,
    ENTER,
    ESC,
    TAB,
    SHIFT_TAB,
    BACKSPACE,
    DELETE,
    APPLY,
    RESTART,
    DESTROY,
    EDIT,
    HELP,
    QUIT,
    REFRESH,
    FOLLOW,
    YES,
    NO
}
