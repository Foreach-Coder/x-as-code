package io.xac.systemd.apply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.apply.actions.AutoStartAction;
import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.EnvFileAction;
import io.xac.systemd.apply.actions.PathLinksAction;
import io.xac.systemd.apply.actions.RestartAction;
import io.xac.systemd.apply.actions.UnitFileAction;
import io.xac.systemd.apply.actions.WorkdirAction;

public final class ApplyRunner {

    /** 顺序是契约的一部分（设计文档 §6.2）：先建链接，再建目录，再写文件，最后动服务。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new PathLinksAction(),
                new WorkdirAction(),
                new EnvFileAction(),
                new UnitFileAction(),
                new DaemonReloadAction(),
                new AutoStartAction(),
                new RestartAction());
    }

    public ApplyOutcome run(ApplyContext ctx) {
        List<StepResult> steps = new ArrayList<StepResult>();
        for (Action action : actions()) {
            StepPlan plan = new StepPlan(action.describe(ctx), false);
            if (ctx.dryRun) {
                steps.add(new StepResult(plan, true, null));
                continue;
            }
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                StepResult failed = new StepResult(plan, false, e.getMessage());
                steps.add(failed);
                return new ApplyOutcome(steps, true, failed);
            }
            steps.add(new StepResult(plan, true, takeWarnings(ctx)));
        }
        return new ApplyOutcome(steps, !ctx.dryRun, null);
    }

    /**
     * 动作的警告由该步的 {@link StepResult#message} 承载，CLI 决定怎么显示；
     * 取走后清空，避免串到下一步。
     */
    private static String takeWarnings(ApplyContext ctx) {
        if (ctx.warnings.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        for (String warning : ctx.warnings) {
            if (message.length() > 0) {
                message.append("; ");
            }
            message.append(warning);
        }
        ctx.warnings.clear();
        return message.toString();
    }
}
