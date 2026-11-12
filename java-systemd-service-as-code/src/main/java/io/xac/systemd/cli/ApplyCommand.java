package io.xac.systemd.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyOutcome;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.apply.StepResult;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true, name = "apply", description = "install or update the service: write files, reload, enable, restart")
public final class ApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "<package-dir>",
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
        return run(packageDir, dryRun, probe, runner, target, out, System.err);
    }

    static int run(Path packageDir, boolean dryRun, EnvironmentProbe probe,
            CommandRunner runner, SystemdTarget target, PrintStream out, PrintStream err) {
        ServicePackage pkg;
        try {
            pkg = ServicePackage.open(packageDir, runner, probe, target);
        } catch (InvalidSpecException e) {
            report(e.violations, err);
            return 2;
        }
        ApplyOutcome outcome;
        try {
            outcome = pkg.apply(dryRun);
        } catch (InvalidSpecException e) {
            report(e.violations, err);
            return 2;
        }
        for (StepResult step : outcome.steps) {
            out.println(outcome.performed
                    ? step.plan.description
                    : "would " + step.plan.description);
            if (step.ok && step.message != null) {
                out.println("warning: " + step.message);
            }
        }
        if (outcome.failed != null) {
            out.println("failed: " + outcome.failed.message);
            return 1;
        }
        return 0;
    }

    private static void report(List<Violation> violations, PrintStream err) {
        for (Violation violation : violations) {
            err.println(violation);
        }
    }
}
