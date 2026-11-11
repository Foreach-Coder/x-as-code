package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class DisableAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl disable " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", "disable", UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            ctx.warnings.add("systemctl disable exit " + result.exitCode + " (ignored)");
        }
    }
}
