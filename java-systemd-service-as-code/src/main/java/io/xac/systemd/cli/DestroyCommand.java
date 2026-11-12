package io.xac.systemd.cli;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.apply.StepResult;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true, name = "destroy", description = "stop and remove the service (unit, env file and path-links)")
public final class DestroyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "<package-dir>",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Option(names = "--dry-run", description = "print the actions only, change nothing")
    boolean dryRun;

    @Option(names = {"-y", "--yes"}, description = "skip confirmation")
    boolean yes;

    @Override
    public Integer call() throws IOException {
        ProcessRunner runner = new ProcessRunner();
        return run(Paths.get(packageDirArg).toAbsolutePath().normalize(), dryRun, yes,
                new RealEnvironmentProbe(runner), runner, SystemdTarget.defaults(),
                System.out, System.err);
    }

    static int run(Path packageDir, boolean dryRun, boolean yes, EnvironmentProbe probe,
                   CommandRunner runner, SystemdTarget target, PrintStream out, PrintStream err)
            throws IOException {
        SpecCommands.Loaded loaded = SpecCommands.loadForDestroy(packageDir, probe);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        DestroyRunner destroyRunner = new DestroyRunner();
        DestroyOutcome plan = destroyRunner.plan(loaded.spec, target);
        printPlan(plan, out);
        if (!yes && !dryRun && !confirm(loaded.spec.name, err)) {
            return 2;
        }
        DestroyOutcome outcome = destroyRunner.run(
                new ApplyContext(loaded.spec, target, runner, dryRun));
        return printOutcome(outcome, dryRun, out);
    }

    /** 在确认之前把删除范围与保留范围讲清楚；--yes 时也打印，便于脚本比较输出。 */
    static void printPlan(DestroyOutcome plan, PrintStream out) {
        out.println("will remove:");
        if (plan.willRemove.isEmpty()) {
            out.println("  (none)");
        }
        for (String path : plan.willRemove) {
            out.println("  " + path);
        }
        out.println("keep: " + String.join(", ", plan.willKeep));
    }

    /** 只负责把结构化结果变成既有 CLI 文案；返回值即退出码。 */
    static int printOutcome(DestroyOutcome outcome, boolean dryRun, PrintStream out) {
        for (StepResult step : outcome.steps) {
            if (step.plan != null) {
                out.println((dryRun ? "would " : "") + step.plan.description);
            }
            if (step.message != null) {
                out.println((step.ok ? "warning: " : "failed: ") + step.message);
            }
        }
        return outcome.failed == null ? 0 : 1;
    }

    private static boolean confirm(String name, PrintStream err) {
        Console console = System.console();
        if (console == null) {
            err.println("use --yes in a non-interactive environment");
            return false;
        }
        String answer = console.readLine("remove unit and env file of %s? [y/N] ", name);
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }
}
