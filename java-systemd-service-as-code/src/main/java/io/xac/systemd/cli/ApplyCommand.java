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

@Command(name = "apply", description = "install or update the service: write files, reload, enable, restart")
public final class ApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Option(names = "--dry-run", description = "print the actions only, change nothing")
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
