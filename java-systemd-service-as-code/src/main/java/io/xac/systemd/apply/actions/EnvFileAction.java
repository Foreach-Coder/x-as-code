package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.FileWriter;
import io.xac.systemd.systemd.UnitNaming;

public final class EnvFileAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        return ctx.spec.env.isEmpty()
                ? "remove " + envPath + " (env is empty)"
                : "write " + envPath + " (0600)";
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        try {
            if (ManagedUnit.existsAndIsForeign(envPath)) {
                throw new ActionFailure(
                        "existing env file is not managed by xac, refusing to overwrite or remove: " + envPath);
            }
            if (ctx.spec.env.isEmpty()) {
                Files.deleteIfExists(envPath);
                return;
            }
            Path dir = envPath.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            FileWriter.write(envPath, ctx.envContent, "rw-------");
        } catch (IOException e) {
            throw new ActionFailure("failed to write env file " + envPath + ": " + e.getMessage(), e);
        }
    }
}
