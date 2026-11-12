package io.xac.systemd.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.core.StatusReport;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true, name = "status", description = "report install and runtime status; exit 0 when in sync, 1 on drift")
public final class StatusCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "<package-dir>",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        ProcessRunner runner = new ProcessRunner();
        return run(packageDir, new RealEnvironmentProbe(runner), runner,
                SystemdTarget.defaults(), System.out, System.err);
    }

    static int run(Path packageDir, EnvironmentProbe probe, CommandRunner runner,
            SystemdTarget target, PrintStream out, PrintStream err) {
        ServicePackage pkg;
        try {
            pkg = ServicePackage.open(packageDir, runner, probe, target);
        } catch (InvalidSpecException e) {
            report(e.violations, err);
            return 2;
        }
        StatusReport statusReport = pkg.status();
        out.print(StatusTextFormatter.format(statusReport.status));
        return statusReport.healthy ? 0 : 1;
    }

    private static void report(List<Violation> violations, PrintStream err) {
        for (Violation violation : violations) {
            err.println(violation);
        }
    }
}
