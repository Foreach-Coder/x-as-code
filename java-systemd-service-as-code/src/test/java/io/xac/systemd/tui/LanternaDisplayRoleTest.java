package io.xac.systemd.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import org.junit.jupiter.api.Test;

/**
 * 颜色契约：Canvas 里的语义角色必须真的落到 Screen 的字符属性上。
 * <p>
 * 这一层和 Canvas 的角色测试分开：Canvas 只记角色，Display 才决定颜色；
 * 哪一边退化都会让这里红。真实终端下的 ANSI 序列由 e2e 的文本快照加人工看
 * {@code target/tui-screens/*.png} 兜底。
 */
class LanternaDisplayRoleTest {

    @Test
    void accentRoleBecomesCyan() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal();
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        LanternaDisplay display = new LanternaDisplay(terminal, screen);
        Canvas canvas = new Canvas(10, 3);
        canvas.text(0, 0, "TITLE", Role.ACCENT);
        display.show(canvas);
        TextCharacter written = terminal.getCharacter(new TerminalPosition(0, 0));
        assertEquals(TextColor.ANSI.CYAN, written.getForegroundColor());
        display.close();
    }

    @Test
    void warningRoleBecomesRed() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal();
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        LanternaDisplay display = new LanternaDisplay(terminal, screen);
        Canvas canvas = new Canvas(10, 3);
        canvas.text(0, 0, "!", Role.WARN);
        display.show(canvas);
        TextCharacter written = terminal.getCharacter(new TerminalPosition(0, 0));
        assertEquals(TextColor.ANSI.RED, written.getForegroundColor());
        display.close();
    }

    @Test
    void selectedRoleBecomesWhiteOnBlue() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal();
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        LanternaDisplay display = new LanternaDisplay(terminal, screen);
        Canvas canvas = new Canvas(10, 3);
        canvas.text(0, 0, "x", Role.SELECTED);
        display.show(canvas);
        TextCharacter written = terminal.getCharacter(new TerminalPosition(0, 0));
        assertEquals(TextColor.ANSI.WHITE, written.getForegroundColor());
        assertEquals(TextColor.ANSI.BLUE, written.getBackgroundColor());
        display.close();
    }
}
