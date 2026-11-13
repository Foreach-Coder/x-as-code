package io.xac.systemd.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Canvas 的角色网格：字符写在哪，角色就要跟到哪；覆盖与清除都不能留下旧角色。
 */
class CanvasRoleTest {

    @Test
    void defaultsToDefaultRole() {
        Canvas canvas = new Canvas(4, 2);

        assertEquals(Role.DEFAULT, canvas.roleAt(0, 0));
        assertEquals(Role.DEFAULT, canvas.roleAt(3, 1));
    }

    @Test
    void textPaintsEveryCellWithTheGivenRole() {
        Canvas canvas = new Canvas(6, 1);

        canvas.text(0, 0, "abc", Role.WARN);

        assertEquals(Role.WARN, canvas.roleAt(0, 0));
        assertEquals(Role.WARN, canvas.roleAt(2, 0));
        assertEquals(Role.DEFAULT, canvas.roleAt(3, 0));
    }

    @Test
    void wideGlyphPlaceholderKeepsTheSameRole() {
        Canvas canvas = new Canvas(4, 1);

        canvas.text(0, 0, "中a", Role.ACCENT);

        assertEquals(Role.ACCENT, canvas.roleAt(0, 0));
        // 第二列是宽字形的占位格，角色与字形一致，Display 跳过写字符但不丢角色。
        assertEquals(Role.ACCENT, canvas.roleAt(1, 0));
        assertEquals(Role.ACCENT, canvas.roleAt(2, 0));
    }

    @Test
    void hlineFillsTheWholeRowWithTheRole() {
        Canvas canvas = new Canvas(3, 2);

        canvas.hline(1, ' ', Role.SELECTED);

        assertEquals(Role.SELECTED, canvas.roleAt(0, 1));
        assertEquals(Role.SELECTED, canvas.roleAt(2, 1));
        assertEquals(Role.DEFAULT, canvas.roleAt(0, 0));
    }

    @Test
    void boxOnlyPaintsItsBorder() {
        Canvas canvas = new Canvas(6, 4);

        canvas.box(1, 1, 4, 3, Role.ACCENT);

        assertEquals(Role.ACCENT, canvas.roleAt(1, 1));
        assertEquals(Role.ACCENT, canvas.roleAt(4, 1));
        assertEquals(Role.ACCENT, canvas.roleAt(1, 3));
        // 内部保持默认角色，面板底色仍由调用方决定。
        assertEquals(Role.DEFAULT, canvas.roleAt(2, 2));
    }

    @Test
    void overwritingACellResetsItsRole() {
        Canvas canvas = new Canvas(2, 1);
        canvas.text(0, 0, "ab", Role.OK);

        canvas.put(0, 0, 'x');

        assertEquals('x', canvas.charAt(0, 0));
        assertEquals(Role.DEFAULT, canvas.roleAt(0, 0));
        // 未被覆盖的邻格角色保留。
        assertEquals(Role.OK, canvas.roleAt(1, 0));
    }

    @Test
    void clearResetsRoles() {
        Canvas canvas = new Canvas(2, 1);
        canvas.text(0, 0, "ab", Role.WARN);

        canvas.clear();

        assertEquals(Role.DEFAULT, canvas.roleAt(0, 0));
        assertEquals(Role.DEFAULT, canvas.roleAt(1, 0));
    }
}
