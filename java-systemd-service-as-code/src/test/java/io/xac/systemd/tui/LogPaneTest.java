package io.xac.systemd.tui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志环形缓冲的纯视图模型契约：跟随/暂停、窗口滑动、容量上限。
 * 不涉及线程、终端与真实日志源。
 */
class LogPaneTest {

    private static LogPane paneWith(int lines) {
        LogPane pane = new LogPane(10);
        for (int i = 1; i <= lines; i++) {
            pane.append("line " + i);
        }
        return pane;
    }

    @Test
    void keepsTailWhileFollowing() {
        LogPane pane = paneWith(5);

        assertTrue(pane.following());
        assertEquals(Arrays.asList("line 4", "line 5"), pane.visible(2, pane.following()));
    }

    @Test
    void dropsOldestLinesAtCapacity() {
        LogPane pane = new LogPane(3);
        for (int i = 1; i <= 5; i++) {
            pane.append("line " + i);
        }

        assertEquals(Arrays.asList("line 3", "line 4", "line 5"), pane.visible(10, true));
    }

    @Test
    void negativeOrNullLinesAreIgnored() {
        LogPane pane = new LogPane(3);
        pane.append(null);
        pane.append("only");

        assertEquals(Arrays.asList("only"), pane.visible(5, true));
    }

    @Test
    void scrollingUpPausesFollowAndMovesWindow() {
        LogPane pane = paneWith(5);

        pane.scrollUp(2);

        assertFalse(pane.following());
        assertEquals(Arrays.asList("line 2", "line 3"), pane.visible(2, pane.following()));
    }

    @Test
    void appendWhilePausedKeepsVisibleWindow() {
        LogPane pane = paneWith(5);
        pane.scrollUp(2);

        pane.append("line 6");

        assertFalse(pane.following());
        assertEquals(Arrays.asList("line 2", "line 3"), pane.visible(2, pane.following()));
    }

    @Test
    void scrollingDownToBottomResumesFollow() {
        LogPane pane = paneWith(5);
        pane.scrollUp(1);

        pane.scrollDown(1);

        assertTrue(pane.following());
        assertEquals(Arrays.asList("line 4", "line 5"), pane.visible(2, pane.following()));
    }

    @Test
    void scrollingUpAtTopStaysAtTop() {
        LogPane pane = paneWith(3);

        pane.scrollUp(100);

        assertFalse(pane.following());
        assertEquals(Arrays.asList("line 1", "line 2"), pane.visible(2, pane.following()));
    }

    @Test
    void toTopShowsOldestAndPauses() {
        LogPane pane = paneWith(5);

        pane.toTop();

        assertFalse(pane.following());
        assertEquals(Arrays.asList("line 1", "line 2"), pane.visible(2, pane.following()));
    }

    @Test
    void toBottomResumesFollow() {
        LogPane pane = paneWith(5);
        pane.toTop();

        pane.toBottom();

        assertTrue(pane.following());
        assertEquals(Arrays.asList("line 4", "line 5"), pane.visible(2, pane.following()));
    }

    @Test
    void toggleFollowPausesAndResumesAtTail() {
        LogPane pane = paneWith(5);

        pane.toggleFollow();
        assertFalse(pane.following());

        pane.toggleFollow();
        assertTrue(pane.following());
        assertEquals(Arrays.asList("line 4", "line 5"), pane.visible(2, pane.following()));
    }

    @Test
    void visibleClampsToAvailableLines() {
        LogPane pane = paneWith(2);

        List<String> visible = pane.visible(10, true);

        assertEquals(Arrays.asList("line 1", "line 2"), visible);
        assertTrue(pane.visible(0, true).isEmpty());
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new LogPane(0));
    }
}
