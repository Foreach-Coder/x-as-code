package io.xac.systemd.cli;

import java.util.concurrent.Callable;

import io.xac.systemd.tui.TuiCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "java-systemd-service-cli",
        mixinStandardHelpOptions = true,
        version = "0.2.3",
        description = "install a Java service as a systemd unit",
        subcommands = {ApplyCommand.class, StatusCommand.class, LogsCommand.class,
                DestroyCommand.class, InitCommand.class, TuiCommand.class})
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
