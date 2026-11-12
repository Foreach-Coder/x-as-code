package io.xac.systemd.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecLoader;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitCommandTest {

    @TempDir
    Path root;

    private static final class Answers implements Prompter {
        private final Deque<String> answers = new ArrayDeque<String>();

        Answers(String... lines) {
            answers.addAll(Arrays.asList(lines));
        }

        @Override
        public String readLine(String prompt) {
            return answers.isEmpty() ? null : answers.removeFirst();
        }
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private Result init(Prompter prompter) throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, "UTF-8");
        PrintStream err = new PrintStream(errBytes, true, "UTF-8");
        int code = InitCommand.run(root.toString(), prompter, out, err);
        return new Result(code, new String(outBytes.toByteArray(), StandardCharsets.UTF_8),
                new String(errBytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void createsProjectWithDefaults() throws Exception {
        Result result = init(new Answers("", "", ""));

        assertEquals(0, result.code);
        assertTrue(result.out.contains("created "), result.out);
        Path yaml = root.resolve("demo").resolve("xac.yaml");
        String content = read(yaml);
        assertTrue(content.contains("name: demo"), content);
        assertTrue(content.contains("jar: app.jar"), content);
        assertTrue(content.contains("java: /usr/bin/java"), content);
        assertTrue(content.contains("# opts:"), content);
        assertTrue(content.contains("# path-links:"), content);
        assertTrue(content.contains("timeout-stop-sec: 60"), content);
        assertTrue(content.endsWith("\n"), "template should end with a newline");
    }

    @Test
    void generatedTemplateIsValid() throws Exception {
        assertEquals(0, init(new Answers("", "", "")).code);
        Path target = root.resolve("demo");

        SpecLoader.Result parsed = SpecLoader.load(target);
        assertNotNull(parsed.spec, "violations: " + parsed.violations);
        List<Violation> violations = new ArrayList<Violation>(parsed.violations);
        violations.addAll(ValidationRunner.run(parsed.spec));
        assertTrue(violations.isEmpty(), "violations: " + violations);
        assertEquals("demo", parsed.spec.name);
        assertEquals(0, parsed.spec.opts.size());
        assertEquals(60, parsed.spec.lifecycle.timeoutStopSec.intValue(),
                "template must match the documented default of xac-yaml-spec.md");
    }

    @Test
    void acceptsCustomAnswers() throws Exception {
        Result result = init(new Answers("billing", "lib/billing.jar", "/opt/jdk8/bin/java"));

        assertEquals(0, result.code);
        String content = read(root.resolve("billing").resolve("xac.yaml"));
        assertTrue(content.contains("name: billing"), content);
        assertTrue(content.contains("jar: lib/billing.jar"), content);
        assertTrue(content.contains("java: /opt/jdk8/bin/java"), content);
    }

    @Test
    void reasksOnInvalidName() throws Exception {
        Result result = init(new Answers("bad/name", "", "", ""));

        assertEquals(0, result.code);
        assertTrue(result.err.contains("invalid service name"), result.err);
        assertTrue(Files.isRegularFile(root.resolve("demo").resolve("xac.yaml")));
    }

    @Test
    void reasksWhenDirectoryIsNotEmpty() throws Exception {
        Path existing = root.resolve("demo");
        Files.createDirectories(existing);
        Files.write(existing.resolve("app.jar"), new byte[] {1});

        Result result = init(new Answers("demo", "other", "", ""));

        assertEquals(0, result.code);
        assertTrue(result.err.contains("not empty"), result.err);
        assertFalse(Files.exists(existing.resolve("xac.yaml")));
        assertTrue(Files.isRegularFile(root.resolve("other").resolve("xac.yaml")));
    }

    @Test
    void refusesMissingParentDirectory() throws Exception {
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, "UTF-8");
        int code = InitCommand.run(root.resolve("nope").toString(), new Answers("", "", ""),
                new PrintStream(new ByteArrayOutputStream()), err);

        assertEquals(2, code);
        assertTrue(errBytes.toString("UTF-8").contains("parent directory does not exist"));
    }

    @Test
    void exitsTwoWhenInputEnds() throws Exception {
        Result result = init(new Answers(""));

        assertEquals(2, result.code);
        assertTrue(result.err.contains("no input"), result.err);
        assertFalse(Files.exists(root.resolve("demo")));
    }

    @Test
    void keepsExistingYamlUntouched() throws Exception {
        Path existing = root.resolve("demo");
        Files.createDirectories(existing);
        Files.write(existing.resolve("xac.yaml"), "keep me".getBytes(StandardCharsets.UTF_8));

        Result result = init(new Answers("demo", "demo2", "", ""));

        assertEquals(0, result.code);
        assertTrue(result.err.contains("already exists"), result.err);
        assertEquals("keep me", read(existing.resolve("xac.yaml")));
        assertTrue(Files.isRegularFile(root.resolve("demo2").resolve("xac.yaml")));
    }
}
