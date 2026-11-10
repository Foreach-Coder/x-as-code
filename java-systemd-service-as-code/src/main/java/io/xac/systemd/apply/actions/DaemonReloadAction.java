package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;

public final class DaemonReloadAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl daemon-reload";
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(Arrays.asList("systemctl", "daemon-reload"));
        if (!result.ok()) {
            throw new ActionFailure("systemctl daemon-reload 失败（退出码 " + result.exitCode
                    + "）: " + result.stderr.trim());
        }
    }
}
