package io.xac.systemd.apply;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ApplyOutcomeTest {

    private static StepResult step(String description, boolean ok, String message) {
        return new StepResult(new StepPlan(description, false), ok, message);
    }

    @Test
    void exposesStepsPerformedAndFirstFailure() {
        StepResult first = step("write unit", true, null);
        StepResult failed = step("systemctl restart", false, "boom");
        List<StepResult> steps = new ArrayList<StepResult>(Arrays.asList(first, failed));

        ApplyOutcome outcome = new ApplyOutcome(steps, true, failed);

        assertEquals(2, outcome.steps.size());
        assertTrue(outcome.performed);
        assertSame(failed, outcome.failed);
    }

    @Test
    void stepsAreAnImmutableCopy() {
        List<StepResult> steps = new ArrayList<StepResult>();
        steps.add(step("write unit", true, null));

        ApplyOutcome outcome = new ApplyOutcome(steps, false, null);
        steps.clear();

        assertEquals(1, outcome.steps.size());
        try {
            outcome.steps.add(step("restart", true, null));
            fail("outcome.steps must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertNull(outcome.failed);
        }
    }

    /** dry-run 步骤：ok=true、message=null；CLI 负责加 would 前缀。 */
    @Test
    void plannedStepCarriesNoMessage() {
        StepResult planned = step("link /srv/a -> /opt/a", true, null);

        assertTrue(planned.ok);
        assertNull(planned.message);
        assertFalse(planned.plan.destructive);
    }

    /** 成功但有警告：ok=true、message 非 null。 */
    @Test
    void completedStepCanCarryAWarning() {
        StepResult warned = step("systemctl disable xac.demo.service", true, "unit is not managed by xac");

        assertTrue(warned.ok);
        assertEquals("unit is not managed by xac", warned.message);
    }
}
