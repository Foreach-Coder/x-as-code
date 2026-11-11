package io.xac.systemd.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.LogsRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "logs", description = "show service logs (journalctl)")
public final class LogsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Option(names = {"-n", "--lines"}, defaultValue = "200",
            description = "number of recent lines (default: 200)")
    int lines;

    @Option(names = {"-f", "--follow"}, description = "follow new log lines")
    boolean follow;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.parse(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        ExecResult result = new LogsRunner()
                .run(loaded.spec, new ProcessRunner(), lines, follow);
        if (!result.ok()) {
            System.err.println(result.stderr.trim());
        } else if (!follow) {
            System.out.print(result.stdout);
        }
        return result.ok() ? 0 : 2;
    }
}
