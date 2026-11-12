package io.xac.systemd.tui;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.googlecode.lanterna.input.InputProvider;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/**
 * 真实终端的按键来源：把 Lanterna 的 KeyStroke 翻译成 NavKey。
 */
public final class LanternaKeySource implements KeySource {

    private static final char CTRL_C = '\u0003';

    private final InputProvider input;

    public LanternaKeySource(InputProvider input) {
        this.input = input;
    }

    @Override
    public KeyEvent next(long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) {
            return map(input.pollInput());
        }
        // Lanterna 3.1.5 的 InputProvider 只有非阻塞 pollInput() 和阻塞 readInput()，
        // 没有带超时的读；这里用短轮询实现超时语义，TUI 的轮询周期只有 50ms。
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            KeyEvent event = map(input.pollInput());
            if (event != null) {
                return event;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    static KeyEvent map(KeyStroke stroke) {
        if (stroke == null) {
            return null;
        }
        KeyType type = stroke.getKeyType();
        if (type == KeyType.Character) {
            char ch = stroke.getCharacter();
            if (ch == CTRL_C) {
                return KeyEvent.of(NavKey.QUIT);
            }
            return KeyEvent.character(ch);
        }
        if (type == null) {
            return null;
        }
        switch (type) {
            case Escape:
                return KeyEvent.of(NavKey.ESC);
            case ArrowUp:
                return KeyEvent.of(NavKey.UP);
            case ArrowDown:
                return KeyEvent.of(NavKey.DOWN);
            case PageUp:
                return KeyEvent.of(NavKey.PGUP);
            case PageDown:
                return KeyEvent.of(NavKey.PGDN);
            case Home:
                return KeyEvent.of(NavKey.TOP);
            case End:
                return KeyEvent.of(NavKey.BOTTOM);
            case Enter:
                return KeyEvent.of(NavKey.ENTER);
            case Tab:
                return KeyEvent.of(NavKey.TAB);
            case Backspace:
                return KeyEvent.of(NavKey.BACKSPACE);
            default:
                return null;
        }
    }
}
