package io.xac.systemd.tui;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LanternaDisplay 的适配层契约：不启动真实终端，Screen/Terminal 用动态代理替身。
 */
class LanternaDisplayTest {

    @Test
    void closeSwallowsScreenAndTerminalFailures() {
        LanternaDisplay display = new LanternaDisplay(
                throwingProxy(Terminal.class), throwingProxy(Screen.class));

        // close 在 TuiCommand 的 finally 里：再抛就会覆盖已经算好的退出码。
        assertDoesNotThrow(display::close);
    }

    @Test
    void showSkipsWideCharacterPlaceholderCells() {
        RecordingScreen recording = new RecordingScreen();
        LanternaDisplay display = new LanternaDisplay(
                throwingProxy(Terminal.class), recording.screen());
        Canvas canvas = new Canvas(3, 1);
        canvas.text(0, 0, "中a");

        display.show(canvas);

        // 第 1 列是宽字形的占位格，不能再 setCharacter，否则会覆盖字形的第二列。
        assertEquals(Arrays.asList(0, 2), recording.columns);
        assertEquals(Arrays.asList("中", "a"), recording.glyphs);
    }

    @Test
    void showRebuildsSurrogatePairsBeforeSettingTheCell() {
        RecordingScreen recording = new RecordingScreen();
        LanternaDisplay display = new LanternaDisplay(
                throwingProxy(Terminal.class), recording.screen());
        Canvas canvas = new Canvas(3, 1);
        canvas.text(0, 0, "\uD83D\uDE00x");

        display.show(canvas);

        // 代理对必须拼成完整字形再写，单独写高代理是非法字符。
        assertEquals(Arrays.asList(0, 2), recording.columns);
        assertEquals(Arrays.asList("\uD83D\uDE00", "x"), recording.glyphs);
    }

    private static final class RecordingScreen {

        final List<Integer> columns = new ArrayList<Integer>();
        final List<String> glyphs = new ArrayList<String>();

        Screen screen() {
            return (Screen) Proxy.newProxyInstance(Screen.class.getClassLoader(),
                    new Class<?>[]{Screen.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("setCharacter".equals(method.getName()) && args != null && args.length == 3) {
                                columns.add((Integer) args[0]);
                                glyphs.add(((TextCharacter) args[2]).getCharacterString());
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T throwingProxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("close".equals(method.getName())) {
                            throw new IOException("terminal gone");
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0f);
        }
        return Double.valueOf(0d);
    }
}
