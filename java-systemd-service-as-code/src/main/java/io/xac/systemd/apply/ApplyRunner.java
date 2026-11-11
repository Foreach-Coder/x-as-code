package io.xac.systemd.apply;

import java.io.PrintStream;
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
                out.println("failed: " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }
}
