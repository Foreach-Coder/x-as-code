package io.xac.systemd.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.LogSourceException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.LogSink;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;
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
        ProcessRunner runner = new ProcessRunner();
        return run(packageDir, runner, new RealEnvironmentProbe(runner),
                SystemdTarget.defaults(), lines, follow, System.out, System.err);
    }

    /**
     * 日志读取由 {@link LogStream} 负责；门面只负责加载、校验、输出与退出码。
     * 非 follow 先收集再一次性输出，保持旧实现的「末尾无换行则补一个」行为。
     */
    static int run(Path packageDir, CommandRunner runner, EnvironmentProbe probe,
                   SystemdTarget target, int lines, boolean follow,
                   final PrintStream out, PrintStream err) {
        ServicePackage pkg;
        try {
            pkg = ServicePackage.open(packageDir, runner, probe, target);
        } catch (InvalidSpecException e) {
            for (Violation violation : e.violations) {
                err.println(violation);
            }
            return 2;
        }

        final StringBuilder collected = new StringBuilder();
        LogSink stdoutSink = new LogSink() {
            @Override
            public void accept(String line) {
                if (follow) {
                    out.print(line);
                    out.print('\n');
                    out.flush();
                } else {
                    collected.append(line).append('\n');
                }
            }
        };
        LogSink stderrSink = new LogSink() {
            @Override
            public void accept(String line) {
                if (follow) {
                    err.print(line);
                    err.print('\n');
                    err.flush();
                }
            }
        };
        try (LogStream stream = pkg.logs(lines, follow)) {
            stream.start(lines, stdoutSink, stderrSink);
        } catch (LogSourceException e) {
            err.println(e.getMessage().trim());
            return 2;
        }
        if (!follow) {
            out.print(collected.toString());
        }
        return 0;
    }
}
