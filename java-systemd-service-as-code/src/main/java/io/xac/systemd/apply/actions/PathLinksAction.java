package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.spec.PathLink;

public final class PathLinksAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "建立 path-links: " + ctx.spec.pathLinks;
    }

    @Override
    public void run(ApplyContext ctx) {
        for (String raw : ctx.spec.pathLinks) {
            PathLink link = PathLink.parse(raw, ctx.spec.packageDir);
            if (link == null) {
                throw new ActionFailure("非法的 path-links 项: " + raw);
            }
            try {
                ensureLink(link);
            } catch (IOException e) {
                throw new ActionFailure("建立软链接失败 " + link.target + ": " + e.getMessage(), e);
            }
        }
    }

    private static void ensureLink(PathLink link) throws IOException {
        Path parent = link.target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("目标的父目录不存在: " + parent);
        }
        if (Files.isSymbolicLink(link.target)) {
            if (Files.readSymbolicLink(link.target).equals(link.source)) {
                return;
            }
            backup(link.target);
        } else if (Files.exists(link.target)) {
            backup(link.target);
        }
        Files.createSymbolicLink(link.target, link.source);
    }

    private static void backup(Path target) throws IOException {
        Path backup = Paths.get(target.toString() + ".bak");
        if (Files.exists(backup)) {
            String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            backup = Paths.get(target.toString() + ".bak." + stamp);
        }
        Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
    }
}
