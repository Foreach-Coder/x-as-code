package io.xac.systemd.cli;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
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

@Command(name = "destroy", description = "停止并移除服务（unit、env 文件与 path-links）")
public final class DestroyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Option(names = "--dry-run", description = "只打印将执行的动作，不做任何改动")
    boolean dryRun;

    @Option(names = {"-y", "--yes"}, description = "跳过确认")
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
        boolean unitExists = Files.exists(unitPath);
        boolean unitManaged = unitExists && ManagedUnit.hasHeader(unitPath);

        out.println("将删除:");
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
            out.println("  (无)");
        }
        if (unitExists && !unitManaged) {
            out.println("将保留: " + unitPath + "（不是 xac 管理的 unit）");
        }
        Path workdir = spec.workdir == null ? spec.packageDir : spec.workdir;
        out.println("保留: 包目录 " + spec.packageDir + "、工作目录 " + workdir
                + "、.bak 备份与日志");
    }

    private boolean confirm(String name) throws IOException {
        Console console = System.console();
        if (console == null) {
            System.err.println("非交互环境请加 --yes");
            return false;
        }
        String answer = console.readLine("确认删除 %s 的 unit 与 env 文件？[y/N] ", name);
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }
}
