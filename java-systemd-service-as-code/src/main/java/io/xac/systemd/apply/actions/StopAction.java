package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class StopAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl stop " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", "stop", UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            ctx.warnings.add("systemctl stop 退出码 " + result.exitCode + "（已忽略）");
        }
    }
}
