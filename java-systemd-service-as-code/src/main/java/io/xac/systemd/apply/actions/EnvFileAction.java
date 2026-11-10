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
                ? "删除 " + envPath + "（env 为空）"
                : "写 " + envPath + " (0600)";
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        try {
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
            throw new ActionFailure("写 env 文件失败 " + envPath + ": " + e.getMessage(), e);
        }
    }
}
