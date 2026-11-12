package io.xac.systemd.tui;

import io.xac.systemd.cli.RootCommand;
import io.xac.systemd.core.ServicePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiCommandTest {

    @TempDir
    Path pkg;

    private static final class RecordingLauncher implements TuiCommand.Launcher {
        ServicePackage launched;
        int calls;

        @Override
        public int launch(ServicePackage servicePackage) {
            this.launched = servicePackage;
            this.calls++;
            return 0;
        }
    }

    private static String text(ByteArrayOutputStream buffer) {
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static PrintStream stream(ByteArrayOutputStream buffer) throws IOException {
        return new PrintStream(buffer, true, "UTF-8");
    }

    private static void assertAscii(String what, String value) {
        for (int i = 0; i < value.length(); i++) {
            assertTrue(value.charAt(i) < 0x80,
                    what + " must be ASCII, found U+" + Integer.toHexString(value.charAt(i)));
        }
    }

    private static void writeValidSpec(Path dir) throws IOException {
        Files.write(dir.resolve("xac.yaml"), String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nonTtyFailsWithOneLineOnStderrAndOpensNoTerminal() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        RecordingLauncher launcher = new RecordingLauncher();

        int code = TuiCommand.run(pkg, false, stream(err), launcher);

        assertEquals(2, code);
        assertEquals(0, launcher.calls, "must not open a terminal without a TTY");
        assertEquals(TuiCommand.NON_TTY_MESSAGE + System.lineSeparator(), text(err));
        assertAscii("non-TTY message", text(err));
    }

    @Test
    void blankTermIsNotAnInteractiveTerminal() {
        assertFalse(TuiCommand.isInteractiveTerminal(true, null));
        assertFalse(TuiCommand.isInteractiveTerminal(true, ""));
        assertFalse(TuiCommand.isInteractiveTerminal(true, "   "));
        assertFalse(TuiCommand.isInteractiveTerminal(false, "xterm-256color"));
        assertTrue(TuiCommand.isInteractiveTerminal(true, "xterm-256color"));
    }

    @Test
    void missingPackageFailsWithViolationsAndOpensNoTerminal() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        RecordingLauncher launcher = new RecordingLauncher();

        int code = TuiCommand.run(pkg, true, stream(err), launcher);

        assertEquals(2, code);
        assertEquals(0, launcher.calls);
        assertTrue(text(err).contains("xac.yaml"), text(err));
    }

    @Test
    void validPackageLaunchesTheApp() throws Exception {
        writeValidSpec(pkg);
        RecordingLauncher launcher = new RecordingLauncher();

        int code = TuiCommand.run(pkg, true, stream(new ByteArrayOutputStream()), launcher);

        assertEquals(0, code);
        assertEquals(1, launcher.calls);
        assertNotNull(launcher.launched);
        assertEquals("demo", launcher.launched.spec().name);
    }

    @Test
    void launcherResultIsPassedThrough() throws Exception {
        writeValidSpec(pkg);
        TuiCommand.Launcher failing = new TuiCommand.Launcher() {
            @Override
            public int launch(ServicePackage servicePackage) {
                return 1;
            }
        };

        assertEquals(1, TuiCommand.run(pkg, true, stream(new ByteArrayOutputStream()), failing));
    }

    @Test
    void rootCommandRegistersTui() {
        assertTrue(RootCommand.newCommandLine().getSubcommands().containsKey("tui"));
    }

    @Test
    void helpWorksWithoutATerminal() {
        StringWriter out = new StringWriter();
        CommandLine cli = RootCommand.newCommandLine();
        cli.setOut(new PrintWriter(out, true));

        assertEquals(0, cli.execute("tui", "--help"));
        assertTrue(out.toString().contains("tui"), out.toString());
    }
}
