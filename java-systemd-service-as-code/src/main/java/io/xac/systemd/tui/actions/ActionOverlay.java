package io.xac.systemd.tui.actions;

import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.StepPlan;
import io.xac.systemd.tui.NavKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动作确认面板的纯视图：文案、可接受的确认键、底部提示都在这里定死，
 * 不碰终端、不碰 {@code ServicePackage}，因此可以脱离 TUI 单独测试。
 *
 * <p>确认键只有 {@link NavKey#YES}（真实按键 {@code y}）：{@code enter}/{@code n}/{@code esc}
 * 一律取消，破坏性动作（destroy）绝不接受回车这种“顺手确认”（design §6.6）。
 */
public final class ActionOverlay {

    private static final String CANCEL_HINT = "  n/esc cancel";

    private final ActionKind kind;
    private final List<String> lines;
    private final String hint;
    private final boolean allowed;

    private ActionOverlay(ActionKind kind, List<String> lines, String hint, boolean allowed) {
        this.kind = kind;
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
        this.hint = hint;
        this.allowed = allowed;
    }

    /** apply 预览：沿用 {@link StepPlan#description}，逐条加 {@code would } 前缀（与 CLI dry-run 一致）。 */
    public static ActionOverlay applyPlan(List<StepPlan> plan) {
        List<String> lines = new ArrayList<String>(plan.size());
        for (StepPlan step : plan) {
            lines.add("would " + step.description);
        }
        return new ActionOverlay(ActionKind.APPLY, lines, "y apply" + CANCEL_HINT, true);
    }

    /** destroy 预览：willRemove / keep / warnings，不要求输入服务名，只认 {@code y}。 */
    public static ActionOverlay destroyPlan(DestroyOutcome plan) {
        List<String> lines = new ArrayList<String>();
        lines.add("will remove:");
        if (plan.willRemove.isEmpty()) {
            lines.add("  (none)");
        }
        for (String path : plan.willRemove) {
            lines.add("  " + path);
        }
        lines.add("keep: " + String.join(", ", plan.willKeep));
        for (String warning : plan.warnings) {
            lines.add("warning: " + warning);
        }
        if (plan.failed != null && plan.failed.message != null
                && !plan.failed.message.isEmpty()) {
            lines.add("failed: " + plan.failed.message);
        }
        return new ActionOverlay(ActionKind.DESTROY, lines, "y destroy" + CANCEL_HINT, true);
    }

    /** restart 确认：动作本身只是 {@code systemctl restart <unit>}，不重写 unit/env。 */
    public static ActionOverlay restartConfirm(String unitName) {
        return new ActionOverlay(ActionKind.RESTART,
                Collections.singletonList("systemctl restart " + unitName),
                "y restart" + CANCEL_HINT, true);
    }

    /**
     * 非 root：面板仍可打开、计划仍可看，但确认键不可用；提示直接说明需要 root（design §6.5）。
     */
    public static ActionOverlay rootRequired(ActionKind kind) {
        return new ActionOverlay(kind, Collections.singletonList("requires root"),
                kind.label() + " requires root", false);
    }

    public ActionKind kind() {
        return kind;
    }

    public List<String> lines() {
        return lines;
    }

    /** 只有面板允许执行且按键是显式 {@code y} 时才接受确认。 */
    public boolean accepts(NavKey key) {
        return allowed && key == NavKey.YES;
    }

    public String hint() {
        return hint;
    }

    public boolean allowed() {
        return allowed;
    }
}
