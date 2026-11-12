package io.xac.systemd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path root;

    private static int execute(String... args) {
        return new CommandLine(new RootCommand()).execute(args);
    }

    @Test
    void printsHelp() {
        assertEquals(0, execute("--help"));
    }

    @Test
    void helpUsesBinaryNameAndListsInit() {
        CommandLine commandLine = new CommandLine(new RootCommand());
        StringWriter output = new StringWriter();
        commandLine.setOut(new PrintWriter(output));

        assertEquals(0, commandLine.execute("--help"));
        String help = output.toString();
        assertTrue(help.contains("java-systemd-service-cli"), help);
        assertTrue(help.contains("init"), help);
    }

    @Test
    void noSubcommandIsUsageError() {
        assertEquals(2, execute());
    }

    @Test
    void missingXacYamlIsInputError() {
        assertEquals(2, execute("apply", root.toString()));
    }

    @Test
    void unknownSubcommandIsUsageError() {
        assertEquals(2, execute("frobnicate"));
    }
}
