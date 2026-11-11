package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.systemd.UnitNaming;

public final class RemoveEnvAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "remove " + UnitNaming.envPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        if (ManagedUnit.existsAndIsForeign(envPath)) {
            ctx.warnings.add("existing env file is not managed by xac, refusing to remove, skipped: " + envPath);
            return;
        }
        try {
            Files.deleteIfExists(envPath);
        } catch (IOException e) {
            throw new ActionFailure("failed to remove env file " + envPath + ": " + e.getMessage(), e);
        }
    }
}
