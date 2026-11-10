package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.FileWriter;
import io.xac.systemd.systemd.UnitNaming;

public final class UnitFileAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "写 " + UnitNaming.unitPath(ctx.spec, ctx.target) + " (0644)";
    }

    @Override
    public void run(ApplyContext ctx) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        try {
            FileWriter.write(unitPath, ctx.unitContent, "rw-r--r--");
        } catch (IOException e) {
            throw new ActionFailure("写 unit 文件失败 " + unitPath + ": " + e.getMessage(), e);
        }
    }
}
