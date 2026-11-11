package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;

public final class WorkdirAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return ctx.spec.workdir == null
                ? "跳过工作目录（未配置 workdir）"
                : "创建工作目录 " + ctx.spec.workdir;
    }

    @Override
    public void run(ApplyContext ctx) {
        Path workdir = ctx.spec.workdir;
        if (workdir == null || Files.isDirectory(workdir)) {
            return;
        }
        Path parent = workdir.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("工作目录的父目录不存在: " + parent);
        }
        try {
            Files.createDirectory(workdir);
        } catch (IOException e) {
            throw new ActionFailure("创建工作目录失败 " + workdir + ": " + e.getMessage(), e);
        }
    }
}
