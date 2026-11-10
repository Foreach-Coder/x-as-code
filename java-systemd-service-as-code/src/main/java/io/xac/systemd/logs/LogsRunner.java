package io.xac.systemd.logs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.UnitNaming;

/**
 * 只读日志入口：普通用户即可运行，绝不调用 root 相关命令。
 *
 * <p>始终带 {@code --no-pager}：否则 journalctl 会交给 less，CLI 在非交互环境下挂死。
 * {@code --follow} 走 {@code runInheritIo}，让 Ctrl-C 直接落到 journalctl 进程；若
 * journalctl 无法启动，把 {@code runInheritIo} 抛出的 {@code IllegalStateException}
 * 收敛成退出码 2 的 {@link ExecResult}，调用方不再需要捕获。
 */
public final class LogsRunner {

    public ExecResult run(ServiceSpec spec, CommandRunner runner, int lines, boolean follow) {
        if (lines <= 0) {
            return new ExecResult(2, "", "-n 必须是正整数");
        }
        List<String> argv = new ArrayList<String>(Arrays.asList(
                "journalctl", "-u", UnitNaming.unitName(spec), "--no-pager", "-n",
                String.valueOf(lines)));
        if (follow) {
            argv.add("--follow");
            try {
                runner.runInheritIo(argv);
            } catch (IllegalStateException e) {
                return new ExecResult(2, "", e.getMessage());
            }
            return new ExecResult(0, "", "");
        }
        return runner.run(argv);
    }
}
