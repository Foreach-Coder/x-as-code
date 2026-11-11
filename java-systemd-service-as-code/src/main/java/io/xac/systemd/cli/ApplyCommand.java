package io.xac.systemd.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.ApplyRunner;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "apply", description = "安装或更新服务：写文件、reload、enable、restart")
public final class ApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Option(names = "--dry-run", description = "只打印将执行的动作，不做任何改动")
    boolean dryRun;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        ProcessRunner runner = new ProcessRunner();
        return run(packageDir, dryRun, new RealEnvironmentProbe(runner), runner,
                SystemdTarget.defaults(), System.out);
    }

    /** 预检失败退出 2，且在任何动作之前返回；--dry-run 也走同一条预检路径。 */
    static int run(Path packageDir, boolean dryRun, EnvironmentProbe probe,
            CommandRunner runner, SystemdTarget target, PrintStream out) {
        SpecCommands.Loaded loaded = SpecCommands.load(packageDir, probe, runner, target);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        ApplyContext ctx = new ApplyContext(loaded.spec, target, runner, dryRun);
        return new ApplyRunner().run(ctx, out);
    }
}
