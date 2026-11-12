package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级 diff 契约：稳定前缀、先删后增、忽略尾空行与 CRLF。
 */
class LineDiffTest {

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    void identicalTextHasOnlySameLines() {
        List<String> diff = LineDiff.diff("a\nb\n", "a\nb\n");

        assertEquals(2, diff.size());
        assertEquals("  a", diff.get(0));
        assertEquals("  b", diff.get(1));
        for (String line : diff) {
            assertTrue(line.startsWith(LineDiff.SAME), line);
        }
    }

    @Test
    void reportsInsertedLineWithPlusPrefix() {
        List<String> diff = LineDiff.diff("a\nb\n", "a\nx\nb\n");

        assertEquals(3, diff.size());
        assertEquals("  a", diff.get(0));
        assertEquals("+ x", diff.get(1));
        assertEquals("  b", diff.get(2));
    }

    @Test
    void reportsDeletedLineWithMinusPrefix() {
        List<String> diff = LineDiff.diff("a\nx\nb\n", "a\nb\n");

        assertEquals(3, diff.size());
        assertEquals("- x", diff.get(1));
    }

    @Test
    void replacementIsDeleteThenInsert() {
        List<String> diff = LineDiff.diff("name: old\n", "name: new\n");

        assertEquals(2, diff.size());
        assertEquals("- name: old", diff.get(0));
        assertEquals("+ name: new", diff.get(1));
    }

    @Test
    void ignoresTrailingBlankLinesAndCarriageReturns() {
        List<String> diff = LineDiff.diff("a\r\nb\r\n\r\n", "a\nb\n");

        assertEquals(2, diff.size());
        assertEquals("  a", diff.get(0));
        assertEquals("  b", diff.get(1));
        assertFalse(join(diff).contains("\r"));
    }

    @Test
    void keepsInteriorBlankLines() {
        List<String> diff = LineDiff.diff("a\n\nb\n", "a\nc\nb\n");

        assertEquals(4, diff.size(), diff.toString());
        assertEquals("  a", diff.get(0));
        // 空行与 c 之间没有可用锚点；删除优先的平局规则允许把空行算作删除。
        assertTrue(diff.get(1).equals(LineDiff.SAME) || diff.get(1).equals("- "), diff.get(1));
        assertTrue(diff.contains("+ c"), diff.toString());
        assertEquals("  b", diff.get(3));
    }

    @Test
    void emptyOldTextIsAllInsertions() {
        List<String> diff = LineDiff.diff("", "a\nb\n");

        assertEquals(2, diff.size());
        assertEquals("+ a", diff.get(0));
        assertEquals("+ b", diff.get(1));
    }

    @Test
    void largeMiddleSegmentStillTerminatesWithDeterministicOrder() {
        // 3000 行全部替换：超过 DP 格子上限，走粗粒度「全删再全增」。
        List<String> before = new ArrayList<String>();
        List<String> after = new ArrayList<String>();
        for (int i = 0; i < 3000; i++) {
            before.add("old-" + i);
            after.add("new-" + i);
        }
        String oldText = join(prefix("  ", before));
        String newText = join(prefix("  ", after));

        List<String> diff = LineDiff.diff(oldText, newText);
        List<String> again = LineDiff.diff(oldText, newText);

        assertEquals(diff, again);
        assertEquals(6000, diff.size());
        assertTrue(diff.get(0).startsWith(LineDiff.DELETE), diff.get(0));
        assertTrue(diff.get(5999).startsWith(LineDiff.INSERT), diff.get(5999));
    }

    private static List<String> prefix(String prefix, List<String> lines) {
        List<String> out = new ArrayList<String>(lines.size());
        for (String line : lines) {
            out.add(prefix + line);
        }
        return out;
    }
}
