package io.xac.systemd.tui;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
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
        this.terminal = terminal;
        this.screen = new TerminalScreen(terminal);
        this.screen.startScreen();
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
                    screen.setCharacter(x, y, new TextCharacter(canvas.charAt(x, y)));
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

    @Override
    public void close() {
        try {
            screen.close();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close TUI screen", e);
        } finally {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 终端已不可用时不要在退出路径上再抛一次
            }
        }
    }
}
