package io.xac.systemd.apply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次 apply 的结果；不可变数据，供 CLI 与后续 TUI 共用。 */
public final class ApplyOutcome {

    public final List<StepResult> steps;
    /** false 表示 dry-run：只计划，未改动任何东西。 */
    public final boolean performed;
    /** 首个失败步；没有失败为 null。 */
    public final StepResult failed;

    public ApplyOutcome(List<StepResult> steps, boolean performed, StepResult failed) {
        this.steps = Collections.unmodifiableList(new ArrayList<StepResult>(steps));
        this.performed = performed;
        this.failed = failed;
    }
}
