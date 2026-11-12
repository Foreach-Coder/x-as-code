package io.xac.systemd.cli;

import io.xac.systemd.exec.RecordingRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsCommandTest {

    @TempDir
    Path pkg;

    @Test
    void missingXacYamlPrintsExactStderrAndExitsTwo() throws Exception {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, "UTF-8");
        PrintStream err = new PrintStream(errBuffer, true, "UTF-8");
        RecordingRunner runner = new RecordingRunner();

        int code = LogsCommand.run(pkg, runner, 10, false, out, err);

        assertEquals(2, code);
        assertEquals("", outBuffer.toString("UTF-8"));
        assertEquals("xac.yaml: xac.yaml not found in package directory"
                        + System.lineSeparator(),
                errBuffer.toString("UTF-8"));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }
}
