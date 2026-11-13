package io.xac.systemd.tui;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

/**
 * 唯一允许出现 Lanterna Terminal/Screen 的地方。TuiApp 只看到 Canvas。
 */
public final class LanternaDisplay implements Display {

    private final Terminal terminal;
    private final Screen screen;

    /**
     * 打开一个真实终端。调用方只依赖 Display 抽象；Terminal 类型不出这个类。
     */
    public static LanternaDisplay open() throws IOException {
        return new LanternaDisplay(new DefaultTerminalFactory().createTerminal());
    }

    private LanternaDisplay(Terminal terminal) throws IOException {
        this(terminal, new TerminalScreen(terminal));
        this.screen.startScreen();
    }

    /**
     * 测试接缝：直接注入 Screen/Terminal 替身，不启动真实终端。
     */
    LanternaDisplay(Terminal terminal, Screen screen) {
        this.terminal = terminal;
        this.screen = screen;
    }

    /**
     * 这个终端上的按键来源。终端创建、按键读取都封在 Lanterna 适配层里。
     */
    public KeySource keys() {
        return new LanternaKeySource(terminal);
    }

    @Override
    public void show(Canvas canvas) {
        try {
            screen.clear();
            for (int y = 0; y < canvas.height(); y++) {
                for (int x = 0; x < canvas.width(); x++) {
                    char c = canvas.charAt(x, y);
                    if (c == Canvas.PLACEHOLDER) {
                        // 宽字形的第二列：字形已经占了这一列，不能再写空格覆盖。
                        continue;
                    }
                    if (Character.isHighSurrogate(c) && x + 1 < canvas.width()
                            && Character.isLowSurrogate(canvas.charAt(x + 1, y))) {
                        String glyph = new String(new char[]{c, canvas.charAt(x + 1, y)});
                        screen.setCharacter(x, y, style(TextCharacter.fromString(glyph)[0],
                                canvas.roleAt(x, y)));
                        x++;
                    } else {
                        screen.setCharacter(x, y, style(new TextCharacter(c), canvas.roleAt(x, y)));
                    }
                }
            }
            screen.setCursorPosition(new TerminalPosition(canvas.cursorX(), canvas.cursorY()));
            screen.refresh();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to draw TUI frame", e);
        }
    }

    @Override
    public Size size() {
        TerminalSize terminalSize = screen.getTerminalSize();
        return new Size(terminalSize.getColumns(), terminalSize.getRows());
    }

    /**
     * 把画布角色映射成终端颜色。这是唯一知道具体配色的地方：
     * Canvas 只记语义角色，换配色不影响绘制代码与测试断言。
     */
    static TextCharacter style(TextCharacter character, Role role) {
        if (role == null || role == Role.DEFAULT) {
            return character;
        }
        switch (role) {
            case ACCENT:
                return character.withForegroundColor(TextColor.ANSI.CYAN);
            case DIM:
                return character.withForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            case WARN:
                return character.withForegroundColor(TextColor.ANSI.RED);
            case OK:
                return character.withForegroundColor(TextColor.ANSI.GREEN);
            case SELECTED:
                return character.withForegroundColor(TextColor.ANSI.WHITE)
                        .withBackgroundColor(TextColor.ANSI.BLUE);
            default:
                return character;
        }
    }

    @Override
    public void close() {
        try {
            screen.close();
        } catch (IOException ignored) {
            // 屏幕/终端已经断开时，退出路径上不要再抛：close 在 finally 里，
            // 抛出去会覆盖 TuiCommand 已经算好的退出码。
        } finally {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }
}
