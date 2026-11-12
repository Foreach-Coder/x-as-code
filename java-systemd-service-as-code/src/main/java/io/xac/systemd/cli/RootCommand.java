package io.xac.systemd.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "xac",
        mixinStandardHelpOptions = true,
        version = "0.1.1",
        description = "install a Java service as a systemd unit",
        subcommands = {ApplyCommand.class, StatusCommand.class, LogsCommand.class,
                DestroyCommand.class})
public final class RootCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 2;
    }

    public static CommandLine newCommandLine() {
        return new CommandLine(new RootCommand());
    }
}
