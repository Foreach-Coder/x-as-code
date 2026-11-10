package io.xac.systemd.cli;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.systemd.SystemdTarget;
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
        SpecCommands.Loaded loaded = SpecCommands.load(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        if (!yes && !dryRun && !confirm(loaded.spec.name)) {
            return 2;
        }
        ApplyContext ctx = new ApplyContext(loaded.spec, SystemdTarget.defaults(),
                new ProcessRunner(), dryRun);
        return new DestroyRunner().run(ctx, System.out);
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
