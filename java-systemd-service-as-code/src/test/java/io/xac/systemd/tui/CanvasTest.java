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

    @Test
    void wideCharacterOccupiesTwoColumns() {
        Canvas canvas = new Canvas(5, 1);
        canvas.text(0, 0, "中a");
        assertEquals('中', canvas.charAt(0, 0));
        assertEquals(Canvas.PLACEHOLDER, canvas.charAt(1, 0));
        assertEquals('a', canvas.charAt(2, 0));
        assertEquals("中\0a  ", canvas.line(0));
        assertEquals(5, canvas.line(0).length());

        // 后续文字从第 3 列继续，不会因为中文只记了 1 个 code unit 而左移。
        canvas.text(3, 0, "b");
        assertEquals('b', canvas.charAt(3, 0));
        assertEquals("中\0ab ", canvas.line(0));
    }

    @Test
    void wideCharacterThatDoesNotFitIsDroppedWithoutHalfAGlyph() {
        Canvas canvas = new Canvas(2, 1);
        canvas.text(1, 0, "中");
        assertEquals("  ", canvas.line(0));

        Canvas oneColumn = new Canvas(1, 1);
        oneColumn.text(0, 0, "中");
        assertEquals(" ", oneColumn.line(0));
    }

    @Test
    void surrogatePairOccupiesTwoColumns() {
        Canvas canvas = new Canvas(4, 1);
        canvas.text(0, 0, "\uD83D\uDE00x");
        assertEquals('\uD83D', canvas.charAt(0, 0));
        assertEquals('\uDE00', canvas.charAt(1, 0));
        assertEquals('x', canvas.charAt(2, 0));
        assertEquals(4, canvas.line(0).length());
    }

    @Test
    void overwritingAWideGlyphLeavesNoPlaceholder() {
        Canvas canvas = new Canvas(3, 1);
        canvas.text(0, 0, "中");
        canvas.text(0, 0, "ab");
        assertEquals("ab ", canvas.line(0));
    }

    @Test
    void overwritingSecondHalfOfSurrogatePairClearsBothCells() {
        Canvas canvas = new Canvas(4, 1);
        canvas.text(0, 0, "\uD83D\uDE00");
        canvas.put(1, 0, 'x');
        // 后半格被覆盖时前半格不能留成孤立高代理。
        assertEquals(' ', canvas.charAt(0, 0));
        assertEquals('x', canvas.charAt(1, 0));
    }

    @Test
    void puttingOverSecondColumnOfCjkGlyphClearsTheWholeGlyph() {
        Canvas canvas = new Canvas(4, 1);
        canvas.text(0, 0, "中");
        canvas.put(1, 0, 'x');
        // 覆盖宽字形的占位格时，字形本身必须一起消失，不能留下半个「中」。
        assertEquals(' ', canvas.charAt(0, 0));
        assertEquals('x', canvas.charAt(1, 0));
        assertEquals(" x  ", canvas.line(0));
    }

    @Test
    void putIgnoresIsolatedSurrogates() {
        Canvas canvas = new Canvas(3, 1);
        canvas.put(0, 0, '\uD83D');
        canvas.put(1, 0, '\uDE00');
        // 孤立代理无法单独成字，写进去只会留下半个代理对。
        assertEquals("   ", canvas.line(0));
    }

    @Test
    void hlineRejectsCellsThatWouldBreakColumnInvariants() {
        Canvas canvas = new Canvas(4, 2);
        IllegalArgumentException wide = assertThrows(IllegalArgumentException.class,
                () -> canvas.hline(0, '中'));
        assertEquals("hline does not accept double-width cells", wide.getMessage());
        assertThrows(IllegalArgumentException.class, () -> canvas.hline(1, '\uD83D'));
        // 拒绝发生在填充之前，画布保持原样。
        assertEquals("    ", canvas.line(0));
        assertEquals("    ", canvas.line(1));
    }
}
