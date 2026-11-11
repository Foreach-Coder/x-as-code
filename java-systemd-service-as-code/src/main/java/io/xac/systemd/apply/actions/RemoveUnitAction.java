package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.systemd.UnitNaming;

public final class RemoveUnitAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "remove " + UnitNaming.unitPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        try {
            if (!Files.exists(unitPath)) {
                return;
            }
            if (!ManagedUnit.hasHeader(unitPath)) {
                ctx.warnings.add("unit is not managed by xac, skipped removal: " + unitPath);
                return;
            }
            Files.delete(unitPath);
        } catch (IOException e) {
            throw new ActionFailure("failed to remove unit file " + unitPath + ": " + e.getMessage(), e);
        }
    }
}
