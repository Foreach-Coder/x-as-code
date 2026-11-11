package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;

public final class DaemonReloadAction implements Action {

    private final boolean ignoreFailure;

    public DaemonReloadAction() {
        this(false);
    }

    /**
     * @param ignoreFailure true 时把非零退出码记为 {@link ApplyContext#warnings}。destroy 用：
     *        文件已经删除，reload 失败不该让拆除整体失败（例如容器里没有运行中的 systemd）。
     */
    public DaemonReloadAction(boolean ignoreFailure) {
        this.ignoreFailure = ignoreFailure;
    }

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl daemon-reload";
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(Arrays.asList("systemctl", "daemon-reload"));
        if (result.ok()) {
            return;
        }
        if (ignoreFailure) {
            ctx.warnings.add("systemctl daemon-reload 退出码 " + result.exitCode + "（已忽略）");
            return;
        }
        throw new ActionFailure("systemctl daemon-reload 失败（退出码 " + result.exitCode
                + "）: " + result.stderr.trim());
    }
}
