package io.xac.systemd.tui;

import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.StepPlan;
import io.xac.systemd.tui.actions.ActionKind;
import io.xac.systemd.tui.actions.ActionOverlay;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作面板的纯视图契约：文案与可接受的确认键都在这里定死，不碰 TuiApp 与终端。
 */
class ActionOverlayTest {

    private static List<StepPlan> sevenSteps() {
        List<StepPlan> steps = new ArrayList<StepPlan>();
        steps.add(new StepPlan("create path-links: []", false));
        steps.add(new StepPlan("skip workdir (not configured)", false));
        steps.add(new StepPlan("remove /etc/xac/xac.demo.env (env is empty)", false));
        steps.add(new StepPlan("write /etc/systemd/system/xac.demo.service (0644)", false));
        steps.add(new StepPlan("systemctl daemon-reload", false));
        steps.add(new StepPlan("systemctl enable xac.demo.service", false));
        steps.add(new StepPlan("systemctl restart xac.demo.service", false));
        return steps;
    }

    @Test
    void applyOverlayListsSevenStepsAndNeedsExplicitYes() {
        ActionOverlay overlay = ActionOverlay.applyPlan(sevenSteps());

        assertEquals(ActionKind.APPLY, overlay.kind());
        assertEquals(7, overlay.lines().size(), overlay.lines().toString());
        assertEquals("would create path-links: []", overlay.lines().get(0));
        assertEquals("would systemctl restart xac.demo.service", overlay.lines().get(6));
        assertTrue(overlay.accepts(NavKey.YES), "y must confirm");
        assertFalse(overlay.accepts(NavKey.ENTER), "enter must not execute apply");
        assertFalse(overlay.accepts(NavKey.NO));
        assertFalse(overlay.accepts(NavKey.ESC));
        assertTrue(overlay.hint().contains("y"), overlay.hint());
        assertTrue(overlay.hint().contains("cancel"), overlay.hint());
    }

    @Test
    void destroyOverlayOnlyAcceptsY() {
        DestroyOutcome plan = new DestroyOutcome(
                Arrays.asList("/etc/systemd/system/xac.demo.service", "/etc/xac/xac.demo.env"),
                Arrays.asList("package directory /srv/demo", "workdir /srv/demo", "logs"),
                Collections.<io.xac.systemd.apply.StepResult>emptyList(),
                Arrays.asList("unit with same name is not managed by xac"),
                null);

        ActionOverlay overlay = ActionOverlay.destroyPlan(plan);

        assertEquals(ActionKind.DESTROY, overlay.kind());
        String panel = String.join("\n", overlay.lines());
        assertTrue(panel.contains("will remove:"), panel);
        assertTrue(panel.contains("/etc/systemd/system/xac.demo.service"), panel);
        assertTrue(panel.contains("/etc/xac/xac.demo.env"), panel);
        assertTrue(panel.contains("keep:"), panel);
        assertTrue(panel.contains("package directory /srv/demo"), panel);
        assertTrue(panel.contains("warning: unit with same name is not managed by xac"), panel);
        assertTrue(overlay.accepts(NavKey.YES), "y must confirm");
        assertFalse(overlay.accepts(NavKey.ENTER), "enter must never confirm destroy");
        assertTrue(overlay.hint().contains("y"), overlay.hint());
    }

    @Test
    void destroyOverlaySaysNoneWhenNothingWillBeRemoved() {
        DestroyOutcome plan = new DestroyOutcome(Collections.<String>emptyList(),
                Arrays.asList("logs"), Collections.<io.xac.systemd.apply.StepResult>emptyList(),
                Collections.<String>emptyList(), null);

        ActionOverlay overlay = ActionOverlay.destroyPlan(plan);

        assertTrue(String.join("\n", overlay.lines()).contains("(none)"),
                overlay.lines().toString());
    }

    @Test
    void restartOverlayNamesTheUnitAndNeedsExplicitYes() {
        ActionOverlay overlay = ActionOverlay.restartConfirm("xac.demo.service");

        assertEquals(ActionKind.RESTART, overlay.kind());
        assertEquals(1, overlay.lines().size(), overlay.lines().toString());
        assertEquals("systemctl restart xac.demo.service", overlay.lines().get(0));
        assertTrue(overlay.accepts(NavKey.YES));
        assertFalse(overlay.accepts(NavKey.ENTER));
        assertTrue(overlay.hint().contains("y"), overlay.hint());
    }

    @Test
    void nonRootOverlayRefusesConfirmation() {
        ActionOverlay overlay = ActionOverlay.rootRequired(ActionKind.APPLY);

        assertEquals(ActionKind.APPLY, overlay.kind());
        assertTrue(overlay.hint().contains("requires root"), overlay.hint());
        assertTrue(String.join("\n", overlay.lines()).contains("requires root"),
                overlay.lines().toString());
        assertFalse(overlay.accepts(NavKey.YES), "non-root must not confirm");
        assertFalse(overlay.accepts(NavKey.ENTER));
    }

    @Test
    void linesAreImmutable() {
        ActionOverlay overlay = ActionOverlay.restartConfirm("xac.demo.service");

        assertThrows(UnsupportedOperationException.class,
                () -> overlay.lines().add("extra"));
    }
}
