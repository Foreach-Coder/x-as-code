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
        return "删除 " + UnitNaming.unitPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        try {
            Files.deleteIfExists(unitPath);
        } catch (IOException e) {
            throw new ActionFailure("删除 unit 文件失败 " + unitPath + ": " + e.getMessage(), e);
        }
    }
}
