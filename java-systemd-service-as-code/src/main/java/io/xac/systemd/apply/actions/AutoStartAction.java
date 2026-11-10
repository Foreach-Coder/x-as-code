package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class AutoStartAction implements Action {

    private static String verb(ApplyContext ctx) {
        return ctx.spec.lifecycle.autoStartOrDefault() ? "enable" : "disable";
    }

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl " + verb(ctx) + " " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        String verb = verb(ctx);
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", verb, UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            throw new ActionFailure("systemctl " + verb + " 失败（退出码 " + result.exitCode
                    + "）: " + result.stderr.trim());
        }
    }
}
