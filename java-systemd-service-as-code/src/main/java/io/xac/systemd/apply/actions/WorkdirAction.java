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
                ? "skip workdir (not configured)"
                : "create workdir " + ctx.spec.workdir;
    }

    @Override
    public void run(ApplyContext ctx) {
        Path workdir = ctx.spec.workdir;
        if (workdir == null || Files.isDirectory(workdir)) {
            return;
        }
        Path parent = workdir.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("parent directory of workdir does not exist: " + parent);
        }
        try {
            Files.createDirectory(workdir);
        } catch (IOException e) {
            throw new ActionFailure("failed to create workdir " + workdir + ": " + e.getMessage(), e);
        }
    }
}
