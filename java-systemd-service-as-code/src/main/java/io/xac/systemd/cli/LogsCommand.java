package io.xac.systemd.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.LogsRunner;
import io.xac.systemd.spec.Violation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true, name = "logs", description = "show service logs (journald, or logs.file when configured)")
public final class LogsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "<package-dir>",
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
        return run(packageDir, new ProcessRunner(), lines, follow, System.out, System.err);
    }

    /** 日志读取仍在 LogsRunner（Task 6 才换成 LogStream）；这里只复用门面的加载与校验。 */
    static int run(Path packageDir, CommandRunner runner, int lines, boolean follow,
                   PrintStream out, PrintStream err) {
        ServicePackage pkg;
        try {
            pkg = ServicePackage.open(packageDir);
        } catch (InvalidSpecException e) {
            for (Violation violation : e.violations) {
                err.println(violation);
            }
            return 2;
        }
        ExecResult result = new LogsRunner().run(pkg.spec(), runner, lines, follow);
        if (!result.ok()) {
            err.println(result.stderr.trim());
        } else if (!follow) {
            String text = result.stdout;
            out.print(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                out.println();
            }
        }
        return result.ok() ? 0 : 2;
    }
}
