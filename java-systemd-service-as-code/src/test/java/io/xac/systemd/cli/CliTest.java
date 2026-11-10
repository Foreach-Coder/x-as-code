package io.xac.systemd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
