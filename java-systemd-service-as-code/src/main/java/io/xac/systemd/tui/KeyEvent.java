package io.xac.systemd.tui;

/**
 * 一个画布坐标上的按键。CHAR 之外的键不携带字符。
 */
public final class KeyEvent {

    public final NavKey key;
    public final char ch;

    public KeyEvent(NavKey key) {
        this(key, '\0');
    }

    public KeyEvent(NavKey key, char ch) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.key = key;
        this.ch = ch;
    }

    public static KeyEvent of(NavKey key) {
        return new KeyEvent(key);
    }

    public static KeyEvent character(char ch) {
        return new KeyEvent(NavKey.CHAR, ch);
    }

    @Override
    public String toString() {
        return key == NavKey.CHAR ? "CHAR(" + ch + ")" : key.name();
    }
}
