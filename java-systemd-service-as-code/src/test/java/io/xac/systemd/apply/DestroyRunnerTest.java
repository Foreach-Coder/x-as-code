package io.xac.systemd.apply;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyRunnerTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("BAR", "b");
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private void install(ServiceSpec spec) throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target()),
                renderer.render(spec, target()).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target()),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stopsDisablesRemovesThenReloads() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        int code = new DestroyRunner().run(
                new ApplyContext(spec, target(), runner, false),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertEquals(0, code);
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "stop", "demo.service"),
                Arrays.asList("systemctl", "disable", "demo.service"),
                Arrays.asList("systemctl", "daemon-reload")), runner.calls());
        assertFalse(Files.exists(UnitNaming.unitPath(spec, target())));
        assertFalse(Files.exists(UnitNaming.envPath(spec, target())));
    }

    @Test
    void continuesWhenDisableFails() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(1, "", "not loaded"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, false),
                new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(buffer.toString("UTF-8").contains("警告"), buffer.toString("UTF-8"));
        assertFalse(Files.exists(UnitNaming.unitPath(spec, target())));
    }

    @Test
    void skipsForeignUnitBeforeStopAndDisable() throws IOException {
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        Path unit = UnitNaming.unitPath(spec, target());
        Files.write(unit, "Description=foreign\n".getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target()),
                new UnitRenderer().renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));
        RecordingRunner runner = new RecordingRunner();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, false),
                new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        String output = buffer.toString("UTF-8");
        assertTrue(output.contains("不是 xac 管理的 unit"), output);
        for (java.util.List<String> call : runner.calls()) {
            assertFalse(call.contains("stop"), call.toString());
            assertFalse(call.contains("disable"), call.toString());
        }
        assertTrue(Files.exists(unit));
        assertFalse(Files.exists(UnitNaming.envPath(spec, target())));
    }

    @Test
    void doesNotStopVendorUnitWithoutOwnedUnitFile() throws IOException {
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        RecordingRunner runner = new RecordingRunner();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, false),
                new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        for (java.util.List<String> call : runner.calls()) {
            assertFalse(call.contains("stop"), call.toString());
            assertFalse(call.contains("disable"), call.toString());
        }
        assertTrue(runner.calls().contains(Arrays.asList("systemctl", "daemon-reload")));
    }

    @Test
    void skipsDanglingSymlinkAtUnitPath() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        Path unit = UnitNaming.unitPath(spec, target());
        Files.createSymbolicLink(unit, root.resolve("gone"));
        RecordingRunner runner = new RecordingRunner();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, false),
                new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        String output = buffer.toString("UTF-8");
        assertTrue(output.contains("unit 路径不是普通文件"), output);
        for (java.util.List<String> call : runner.calls()) {
            assertFalse(call.contains("stop"), call.toString());
            assertFalse(call.contains("disable"), call.toString());
        }
        assertTrue(Files.isSymbolicLink(unit));
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, true),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(runner.calls().isEmpty());
        assertTrue(Files.exists(UnitNaming.unitPath(spec, target())));
    }

    @Test
    void removesLinkOnlyWhenItPointsToSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Files.createDirectories(target().unitDir);
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);

        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .pathLinks(new ArrayList<String>(Arrays.asList(source + ":" + linked)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();

        new DestroyRunner().run(new ApplyContext(spec, target(), new RecordingRunner(), false),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertFalse(Files.exists(linked));
        assertTrue(Files.isDirectory(source));
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("probe-link");
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
