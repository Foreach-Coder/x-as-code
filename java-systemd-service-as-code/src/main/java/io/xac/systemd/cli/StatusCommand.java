package io.xac.systemd.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.status.StatusRunner;
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
        SpecCommands.Loaded loaded = SpecCommands.parse(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        StatusResult result = new StatusRunner().run(loaded.spec, SystemdTarget.defaults(),
                new ProcessRunner());
        System.out.print(result.format());
        return result.healthy() ? 0 : 1;
    }
}
