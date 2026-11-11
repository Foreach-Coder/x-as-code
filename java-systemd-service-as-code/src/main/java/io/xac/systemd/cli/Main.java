package io.xac.systemd.cli;

import picocli.CommandLine;

public final class Main {

    public static void main(String[] args) {
        System.exit(new CommandLine(new RootCommand()).execute(args));
    }

    private Main() {
    }
}
