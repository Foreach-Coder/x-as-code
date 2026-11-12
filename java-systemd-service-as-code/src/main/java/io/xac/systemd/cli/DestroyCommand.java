package io.xac.systemd.cli;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.apply.actions.ManagedUnit;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true, name = "destroy", description = "stop and remove the service (unit, env file and path-links)")
public final class DestroyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Option(names = "--dry-run", description = "print the actions only, change nothing")
    boolean dryRun;

    @Option(names = {"-y", "--yes"}, description = "skip confirmation")
    boolean yes;

    @Override
    public Integer call() throws IOException {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.loadForDestroy(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        SystemdTarget target = SystemdTarget.defaults();
        printPlan(loaded.spec, target, System.out);
        if (!yes && !dryRun && !confirm(loaded.spec.name)) {
            return 2;
        }
        ApplyContext ctx = new ApplyContext(loaded.spec, target, new ProcessRunner(), dryRun);
        return new DestroyRunner().run(ctx, System.out);
    }

    /** 在确认之前把删除范围与保留范围讲清楚；--yes 时也打印，便于脚本比较输出。 */
    static void printPlan(ServiceSpec spec, SystemdTarget target, PrintStream out) throws IOException {
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);
        // 与 DestroyRunner 一致：只有 unitPath 上的普通文件且带管理头，才会被 stop/disable/删除。
        boolean unitManaged = Files.isRegularFile(unitPath, LinkOption.NOFOLLOW_LINKS)
                && ManagedUnit.hasHeader(unitPath);

        out.println("will remove:");
        boolean any = false;
        if (unitManaged) {
            out.println("  " + unitPath);
            any = true;
        }
        if (Files.exists(envPath)) {
            out.println("  " + envPath);
            any = true;
        }
        for (String raw : spec.pathLinks) {
            PathLink link = PathLink.parse(raw, spec.packageDir);
            if (link == null) {
                continue;
            }
            if (Files.isSymbolicLink(link.target)
                    && Files.readSymbolicLink(link.target).equals(link.source)) {
                out.println("  " + link.target.toAbsolutePath().normalize());
                any = true;
            }
        }
        if (!any) {
            out.println("  (none)");
        }
        Path workdir = spec.workdir == null ? spec.packageDir : spec.workdir;
        out.println("keep: package directory " + spec.packageDir + ", workdir " + workdir + ", logs");
    }

    private boolean confirm(String name) throws IOException {
        Console console = System.console();
        if (console == null) {
            System.err.println("use --yes in a non-interactive environment");
            return false;
        }
        String answer = console.readLine("remove unit and env file of %s? [y/N] ", name);
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }
}
