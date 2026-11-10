package io.xac.systemd.apply;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.DisableAction;
import io.xac.systemd.apply.actions.RemoveEnvAction;
import io.xac.systemd.apply.actions.RemoveLinksAction;
import io.xac.systemd.apply.actions.RemoveUnitAction;
import io.xac.systemd.apply.actions.StopAction;

public final class DestroyRunner {

    /** 顺序（设计文档 §6.5）：先停服务，再删文件，最后移除链接。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new StopAction(),
                new DisableAction(),
                new RemoveUnitAction(),
                new RemoveEnvAction(),
                // destroy 的语义是「让这些东西不存在」；reload 失败只警告（容器/无 systemd 场景）。
                new DaemonReloadAction(true),
                new RemoveLinksAction());
    }

    public int run(ApplyContext ctx, PrintStream out) {
        for (Action action : actions()) {
            String description = action.describe(ctx);
            if (ctx.dryRun) {
                out.println("would " + description);
                continue;
            }
            out.println(description);
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                out.println("失败: " + e.getMessage());
                return 1;
            }
            for (String warning : ctx.warnings) {
                out.println("警告: " + warning);
            }
            ctx.warnings.clear();
        }
        return 0;
    }
}
