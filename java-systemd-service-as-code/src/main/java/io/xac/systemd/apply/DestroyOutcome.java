package io.xac.systemd.apply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * destroy 的结构化结果。
 *
 * <p>{@link #willRemove} 与 {@link #willKeep} 是「计划」视角，{@link #steps}、
 * {@link #warnings}、{@link #failed} 是「执行」视角；CLI 只负责把它们打印出来。
 */
public final class DestroyOutcome {

    /** 会被删除的路径（绝对路径字符串，顺序与打印顺序一致）。 */
    public final List<String> willRemove;

    /** 会保留的内容，形如 {@code package directory …, workdir …, logs}。 */
    public final List<String> willKeep;

    /**
     * 执行过的步骤；warning-only 步骤的 {@code plan} 为 null，只携带 {@code message}。
     * 消费方必须判空：只有 {@code plan != null} 的步骤才有动作描述。
     */
    public final List<StepResult> steps;

    /**
     * 所有已打印警告的汇总，按输出顺序排列。失败即停止：失败前未打印的
     * {@code ApplyContext.warnings} 不会进入这里。
     */
    public final List<String> warnings;

    /** 失败的那一步；全部成功时为 null。 */
    public final StepResult failed;

    public DestroyOutcome(List<String> willRemove, List<String> willKeep,
                          List<StepResult> steps, List<String> warnings, StepResult failed) {
        this.willRemove = Collections.unmodifiableList(new ArrayList<String>(willRemove));
        this.willKeep = Collections.unmodifiableList(new ArrayList<String>(willKeep));
        this.steps = Collections.unmodifiableList(new ArrayList<StepResult>(steps));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.failed = failed;
    }
}
