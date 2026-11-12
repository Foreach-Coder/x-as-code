package io.xac.systemd.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasTest {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void newCanvasIsBlank() {
        Canvas canvas = new Canvas(5, 3);
        assertEquals(5, canvas.width());
        assertEquals(3, canvas.height());
        for (int y = 0; y < 3; y++) {
            assertEquals("     ", canvas.line(y));
        }
        assertEquals(0, canvas.cursorX());
        assertEquals(0, canvas.cursorY());
    }

    @Test
    void textWritesAtPositionAndDoesNotWrap() {
        Canvas canvas = new Canvas(5, 2);
        canvas.text(0, 0, "abcdef");
        assertEquals("abcde", canvas.line(0));
        assertEquals("     ", canvas.line(1));
    }

    @Test
    void textClipsAtLeftEdge() {
        Canvas canvas = new Canvas(5, 1);
        canvas.text(-2, 0, "abcde");
        assertEquals("cde  ", canvas.line(0));
    }

    @Test
    void textClipsAtRightEdge() {
        Canvas canvas = new Canvas(5, 1);
        canvas.text(3, 0, "ab");
        assertEquals("   ab", canvas.line(0));
        canvas.text(4, 0, "xyz");
        assertEquals("   ax", canvas.line(0));
    }

    @Test
    void textIgnoresRowsOutsideCanvas() {
        Canvas canvas = new Canvas(5, 2);
        canvas.text(0, -1, "abc");
        canvas.text(0, 2, "abc");
        assertEquals("     ", canvas.line(0));
        assertEquals("     ", canvas.line(1));
    }

    @Test
    void textIgnoresNull() {
        Canvas canvas = new Canvas(3, 1);
        canvas.text(0, 0, null);
        assertEquals("   ", canvas.line(0));
    }

    @Test
    void hlineFillsTheWholeRow() {
        Canvas canvas = new Canvas(5, 2);
        canvas.hline(1, '=');
        assertEquals("     ", canvas.line(0));
        assertEquals("=====", canvas.line(1));
        assertEquals(' ', canvas.charAt(0, 0));
    }

    @Test
    void hlineOutsideCanvasIsIgnored() {
        Canvas canvas = new Canvas(5, 2);
        canvas.hline(-1, '=');
        canvas.hline(2, '=');
        assertEquals("     ", canvas.line(0));
        assertEquals("     ", canvas.line(1));
    }

    @Test
    void boxDrawsCornersAndEdges() {
        Canvas canvas = new Canvas(6, 4);
        canvas.box(0, 0, 6, 4);
        assertEquals("┌────┐", canvas.line(0));
        assertEquals("│    │", canvas.line(1));
        assertEquals("│    │", canvas.line(2));
        assertEquals("└────┘", canvas.line(3));
        assertEquals(' ', canvas.charAt(2, 1));
    }

    @Test
    void boxClipsToCanvas() {
        Canvas canvas = new Canvas(6, 4);
        canvas.box(1, 1, 3, 2);
        assertEquals(" ┌─┐  ", canvas.line(1));
        assertEquals(" └─┘  ", canvas.line(2));
    }

    @Test
    void lineRejectsOutOfRangeRows() {
        Canvas canvas = new Canvas(3, 2);
        assertThrows(IllegalArgumentException.class, () -> canvas.line(-1));
        assertThrows(IllegalArgumentException.class, () -> canvas.line(2));
    }

    @Test
    void clearBlanksEveryCell() {
        Canvas canvas = new Canvas(3, 2);
        canvas.text(0, 0, "abc");
        canvas.clear();
        assertEquals("   ", canvas.line(0));
        assertEquals("   ", canvas.line(1));
    }

    @Test
    void cursorIsClampedToCanvas() {
        Canvas canvas = new Canvas(4, 2);
        canvas.setCursor(99, 99);
        assertEquals(3, canvas.cursorX());
        assertEquals(1, canvas.cursorY());
        canvas.setCursor(-5, -5);
        assertEquals(0, canvas.cursorX());
        assertEquals(0, canvas.cursorY());
    }

    @Test
    void putClipsAndWritesSingleCharacters() {
        Canvas canvas = new Canvas(3, 1);
        canvas.put(1, 0, 'x');
        canvas.put(9, 0, 'y');
        assertEquals(" x ", canvas.line(0));
        assertEquals(' ', canvas.charAt(9, 0));
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new Canvas(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Canvas(1, 0));
    }

    @Test
    void lineIsAlwaysExactlyWidthCharacters() {
        Canvas canvas = new Canvas(7, 1);
        canvas.text(0, 0, "ab");
        assertEquals(7, canvas.line(0).length());
        assertTrue(canvas.line(0).startsWith("ab" + repeat(' ', 5)));
    }
}
