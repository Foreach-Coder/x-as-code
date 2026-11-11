package io.xac.systemd.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecLoaderTest {

    @TempDir
    Path pkg;

    private Path write(String yaml) throws IOException {
        Path file = pkg.resolve("xac.yaml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String minimal() {
        return String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
    }

    @Test
    void loadsMinimalSpec() throws IOException {
        write(minimal());
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertTrue(result.violations.isEmpty(), result.violations.toString());
        assertNotNull(result.spec);
        assertEquals("demo", result.spec.name);
        assertEquals(pkg.resolve("app.jar").toAbsolutePath().normalize(), result.spec.jar);
        assertEquals(1, result.spec.opts.size());
        assertEquals("-Xmx512m", result.spec.opts.get(0));
        assertEquals("on-failure", result.spec.lifecycle.restartOrDefault());
        assertEquals(5, result.spec.lifecycle.restartSecOrDefault());
        assertEquals(60, result.spec.lifecycle.timeoutStopSecOrDefault());
        assertTrue(result.spec.lifecycle.autoStartOrDefault());
    }

    @Test
    void rejectsUnknownFieldWithPath() throws IOException {
        write(minimal().replace("name: demo", "name: demo\nnarration: 说明"));
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertEquals("narration: unknown field", result.violations.get(0).toString());
    }

    @Test
    void rejectsWrongTypeWithPath() throws IOException {
        write(minimal().replace("    - -Xmx512m", "  opts: -Xmx512m"));
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.get(0).toString().startsWith("runtime.opts"),
                result.violations.toString());
    }

    @Test
    void reportsMissingRequiredFields() throws IOException {
        write("name: demo\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.toString().contains("artifact"));
        assertTrue(result.violations.toString().contains("runtime"));
    }

    @Test
    void keepsYaml12TextForRestartNo() throws IOException {
        write(minimal() + "lifecycle:\n  restart: no\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertTrue(result.violations.isEmpty(), result.violations.toString());
        assertEquals("no", result.spec.lifecycle.restart);
    }

    @Test
    void resolvesWorkdirRelativeToPackageDir() throws IOException {
        write(minimal() + "workdir: run\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertEquals(pkg.resolve("run").toAbsolutePath().normalize(), result.spec.workdir);
    }

    @Test
    void rejectsDuplicateEnvKey() throws IOException {
        write(minimal() + "env:\n  A: 1\n  A: 2\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.toString().contains("A"), result.violations.toString());
    }

    @Test
    void reportsDuplicateEnvKeyPath() throws IOException {
        write(minimal() + "env:\n  A: one\n  A: two\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertEquals("env.A: duplicate field", result.violations.get(0).toString());
    }

    @Test
    void rejectsNullEnvValue() throws IOException {
        write(minimal() + "env:\n  SECRET:\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertEquals("env.SECRET: must be a string", result.violations.get(0).toString());
    }

    @Test
    void builderResolvesJarAndWorkdirAgainstPackageDir() {
        ServiceSpec spec = ServiceSpec.builder(pkg).jar("app.jar").workdir("run").build();
        assertEquals(pkg.resolve("app.jar").toAbsolutePath().normalize(), spec.jar);
        assertEquals(pkg.resolve("run").toAbsolutePath().normalize(), spec.workdir);
    }
}
