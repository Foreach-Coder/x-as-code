package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.PathLinkGuard;

public final class PathLinksAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "create path-links: " + ctx.spec.pathLinks;
    }

    @Override
    public void run(ApplyContext ctx) {
        for (int i = 0; i < ctx.spec.pathLinks.size(); i++) {
            String raw = ctx.spec.pathLinks.get(i);
            PathLink link = PathLink.parse(raw, ctx.spec.packageDir);
            if (link == null) {
                throw new ActionFailure("invalid path-links entry: " + raw);
            }
            String problem = PathLinkGuard.check(link, ctx.target.unitDir, ctx.target.envDir,
                    ctx.spec.packageDir);
            if (problem != null) {
                throw new ActionFailure("path-links[" + i + "]: " + problem);
            }
            try {
                ensureLink(link);
            } catch (IOException e) {
                throw new ActionFailure("failed to create symlink " + link.target + ": " + e.getMessage(), e);
            }
        }
    }

    private static void ensureLink(PathLink link) throws IOException {
        Path parent = link.target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("parent directory of target does not exist: " + parent);
        }
        // 目标不存在才建链接；已是正确的软链接则跳过；其余形态一律拒绝，绝不移动用户数据。
        String problem = PathLinkGuard.checkTarget(link);
        if (problem != null) {
            throw new ActionFailure(problem);
        }
        if (Files.isSymbolicLink(link.target)) {
            return;
        }
        Files.createSymbolicLink(link.target, link.source);
    }
}
