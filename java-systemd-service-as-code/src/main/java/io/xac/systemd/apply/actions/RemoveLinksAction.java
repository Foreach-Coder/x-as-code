package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.spec.PathLink;

public final class RemoveLinksAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "移除 path-links: " + ctx.spec.pathLinks;
    }

    @Override
    public void run(ApplyContext ctx) {
        for (String raw : ctx.spec.pathLinks) {
            PathLink link = PathLink.parse(raw, ctx.spec.packageDir);
            if (link == null) {
                continue;
            }
            try {
                // 只删自己建的链接；真实目录/文件一律不碰。
                if (Files.isSymbolicLink(link.target)
                        && Files.readSymbolicLink(link.target).equals(link.source)) {
                    Files.delete(link.target);
                }
            } catch (IOException e) {
                throw new ActionFailure("移除软链接失败 " + link.target + ": " + e.getMessage(), e);
            }
        }
    }
}
