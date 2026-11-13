package io.xac.systemd.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import com.googlecode.lanterna.input.InputProvider;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

/**
 * 只测 KeyStroke 到 NavKey 的翻译，不打开任何真实终端。
 */
class LanternaKeySourceTest {

    @Test
    void mapsCharacterKeys() throws IOException {
        KeySource keys = new LanternaKeySource(new InputProvider() {
            @Override
            public KeyStroke pollInput() {
                return new KeyStroke('q', false, false);
            }

            @Override
            public KeyStroke readInput() {
                return null;
            }
        });

        KeyEvent event = keys.next(0);

        assertNotNull(event);
        assertEquals(NavKey.CHAR, event.key);
        assertEquals('q', event.ch);
    }

    @Test
    void ctrlCIsQuit() {
        KeyEvent event = LanternaKeySource.map(new KeyStroke('\u0003', true, false));

        assertNotNull(event);
        assertEquals(NavKey.QUIT, event.key);
    }

    @Test
    void mapsNavigationKeys() {
        assertKey(KeyType.Escape, NavKey.ESC);
        assertKey(KeyType.ArrowUp, NavKey.UP);
        assertKey(KeyType.ArrowDown, NavKey.DOWN);
        assertKey(KeyType.ArrowLeft, NavKey.LEFT);
        assertKey(KeyType.ArrowRight, NavKey.RIGHT);
        assertKey(KeyType.PageUp, NavKey.PGUP);
        assertKey(KeyType.PageDown, NavKey.PGDN);
        assertKey(KeyType.Home, NavKey.TOP);
        assertKey(KeyType.End, NavKey.BOTTOM);
        assertKey(KeyType.Enter, NavKey.ENTER);
        assertKey(KeyType.Tab, NavKey.TAB);
        assertKey(KeyType.ReverseTab, NavKey.SHIFT_TAB);
        assertKey(KeyType.Backspace, NavKey.BACKSPACE);
    }

    @Test
    void ignoresUnknownKeys() {
        assertNull(LanternaKeySource.map(null));
        assertNull(LanternaKeySource.map(new KeyStroke(KeyType.F1)));
    }

    @Test
    void timeoutWithoutInputReturnsNull() throws IOException {
        KeySource keys = new LanternaKeySource(new InputProvider() {
            @Override
            public KeyStroke pollInput() {
                return null;
            }

            @Override
            public KeyStroke readInput() {
                return null;
            }
        });

        assertNull(keys.next(0));
        assertNull(keys.next(1));
    }

    private static void assertKey(KeyType type, NavKey expected) {
        KeyEvent event = LanternaKeySource.map(new KeyStroke(type));
        assertNotNull(event, "no mapping for " + type);
        assertEquals(expected, event.key, "mapping for " + type);
    }
}
