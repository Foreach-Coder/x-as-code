package io.xac.systemd.apply;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.DisableAction;
import io.xac.systemd.apply.actions.ManagedUnit;
import io.xac.systemd.apply.actions.RemoveEnvAction;
import io.xac.systemd.apply.actions.RemoveLinksAction;
import io.xac.systemd.apply.actions.RemoveUnitAction;
import io.xac.systemd.apply.actions.StopAction;
import io.xac.systemd.systemd.UnitNaming;

public final class DestroyRunner {

    /** 顺序（设计文档 §6.5）：先停服务，再删文件，最后移除链接。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new StopAction(),
                new DisableAction(),
                new RemoveUnitAction(),
                new RemoveEnvAction(),
                // destroy 的语义是「让这些东西不存在」；reload 失败只警告（容器/无 systemd 场景）。
                new DaemonReloadAction(true),
                new RemoveLinksAction());
    }

    public int run(ApplyContext ctx, PrintStream out) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        // 只有「unitPath 上确实存在由 xac 管理的普通文件」才证明归属；否则 systemd 可能
        // 从 /usr/lib/systemd/system 加载了同名 unit（如 ssh），绝不能 stop/disable 它。
        boolean pathExists = Files.exists(unitPath, LinkOption.NOFOLLOW_LINKS);
        boolean regularFile = Files.isRegularFile(unitPath, LinkOption.NOFOLLOW_LINKS);
        boolean managed = false;
        if (regularFile) {
            try {
                managed = ManagedUnit.hasHeader(unitPath);
            } catch (IOException e) {
                throw new ActionFailure("读取 unit 文件失败 " + unitPath + ": " + e.getMessage(), e);
            }
        }
        boolean skipUnitActions = !managed;
        if (skipUnitActions) {
            // 无法证明归属就不 stop/disable，但必须让用户知道可能还有同名进程在跑。
            ctx.warnings.add("同名 unit 不由 xac 管理，未停止；如仍有进程请手动处理: " + unitPath);
        }
        if (pathExists && !regularFile) {
            out.println("警告: unit 路径不是普通文件，已跳过: " + unitPath);
        } else if (regularFile && !managed) {
            // 归属判断必须发生在 stop / disable 之前，否则会先动别人的服务再只发一条警告。
            out.println("警告: 不是 xac 管理的 unit，已跳过停止/禁用/删除: " + unitPath);
        }
        for (Action action : actions()) {
            if (skipUnitActions && isUnitAction(action)) {
                continue;
            }
            String description = action.describe(ctx);
            if (ctx.dryRun) {
                out.println("would " + description);
                continue;
            }
            out.println(description);
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                out.println("失败: " + e.getMessage());
                return 1;
            }
            for (String warning : ctx.warnings) {
                out.println("警告: " + warning);
            }
            ctx.warnings.clear();
        }
        return 0;
    }

    private static boolean isUnitAction(Action action) {
        return action instanceof StopAction
                || action instanceof DisableAction
                || action instanceof RemoveUnitAction;
    }
}
