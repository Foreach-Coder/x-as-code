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
        return "删除 " + UnitNaming.envPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        try {
            Files.deleteIfExists(envPath);
        } catch (IOException e) {
            throw new ActionFailure("删除 env 文件失败 " + envPath + ": " + e.getMessage(), e);
        }
    }
}
