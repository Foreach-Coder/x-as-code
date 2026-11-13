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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyRunnerTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(String... links) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("BAR", "b");
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .pathLinks(new ArrayList<String>(Arrays.asList(links)))
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

    private DestroyOutcome run(ServiceSpec spec, RecordingRunner runner, boolean dryRun) {
        return new DestroyRunner().run(new ApplyContext(spec, target(), runner, dryRun));
    }

    private static List<String> descriptions(DestroyOutcome outcome) {
        List<String> result = new ArrayList<String>();
        for (StepResult step : outcome.steps) {
            if (step.plan != null) {
                result.add(step.plan.description);
            }
        }
        return result;
    }

    @Test
    void stopsDisablesRemovesThenReloads() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        DestroyOutcome outcome = run(spec, runner, false);

        assertNull(outcome.failed);
        assertEquals(6, outcome.steps.size());
        assertEquals(Arrays.asList(
                "systemctl stop xac.demo.service",
                "systemctl disable xac.demo.service",
                "remove " + UnitNaming.unitPath(spec, target()),
                "remove " + UnitNaming.envPath(spec, target()),
                "systemctl daemon-reload",
                "remove path-links: []"), descriptions(outcome));
        assertTrue(outcome.warnings.isEmpty());
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "stop", "xac.demo.service"),
                Arrays.asList("systemctl", "disable", "xac.demo.service"),
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

        DestroyOutcome outcome = run(spec, runner, false);

        assertNull(outcome.failed);
        assertEquals("systemctl stop exit 1 (ignored)", outcome.steps.get(0).message);
        assertEquals("systemctl disable exit 1 (ignored)", outcome.steps.get(1).message);
        assertEquals("systemctl daemon-reload exit 1 (ignored)", outcome.steps.get(4).message);
        assertEquals(3, outcome.warnings.size());
        assertFalse(Files.exists(UnitNaming.unitPath(spec, target())));
    }

    @Test
    void planListsManagedUnitEnvAndMatchingLinksOnly() throws IOException {
        Assumptions.assumeTrue(File.separatorChar == '/');
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);

        ServiceSpec spec = spec(source + ":" + linked);
        install(spec);

        DestroyOutcome plan = new DestroyRunner().plan(spec, target());

        assertEquals(Arrays.asList(
                UnitNaming.unitPath(spec, target()).toString(),
                UnitNaming.envPath(spec, target()).toString(),
                linked.toAbsolutePath().normalize().toString()), plan.willRemove);
        assertEquals(Arrays.asList(
                "package directory " + root.toAbsolutePath().normalize(),
                "workdir " + root.toAbsolutePath().normalize(),
                "logs"), plan.willKeep);
        assertTrue(plan.steps.isEmpty());
        assertTrue(plan.warnings.isEmpty());
        assertNull(plan.failed);
        assertTrue(Files.exists(UnitNaming.unitPath(spec, target())));
        assertTrue(Files.exists(linked));
    }

    @Test
    void planLeavesForeignUnitOutOfRemoveList() throws IOException {
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        Files.write(UnitNaming.unitPath(spec, target()),
                "Description=foreign\n".getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target()),
                new UnitRenderer().renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        DestroyOutcome plan = new DestroyRunner().plan(spec, target());

        assertEquals(Arrays.asList(UnitNaming.envPath(spec, target()).toString()), plan.willRemove);
        assertNull(plan.failed);
    }

    @Test
    void foreignUnitSkipsStopDisableAndWarns() throws IOException {
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        Path unit = UnitNaming.unitPath(spec, target());
        Files.write(unit, "Description=foreign\n".getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target()),
                new UnitRenderer().renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));
        RecordingRunner runner = new RecordingRunner();

        DestroyOutcome outcome = run(spec, runner, false);

        String skipped = "unit is not managed by xac, skipped stop/disable/remove: " + unit;
        String notStopped = "unit with same name is not managed by xac, not stopped; "
                + "stop it manually if a process is still running: " + unit;
        assertNull(outcome.failed);
        assertEquals(4, outcome.steps.size());
        assertNull(outcome.steps.get(0).plan);
        assertEquals(skipped, outcome.steps.get(0).message);
        assertEquals("remove " + UnitNaming.envPath(spec, target()),
                outcome.steps.get(1).plan.description);
        assertEquals(notStopped, outcome.steps.get(1).message);
        assertEquals(Arrays.asList(skipped, notStopped), outcome.warnings);
        for (List<String> call : runner.calls()) {
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

        DestroyOutcome outcome = run(spec, runner, false);

        String notStopped = "unit with same name is not managed by xac, not stopped; "
                + "stop it manually if a process is still running: " + UnitNaming.unitPath(spec, target());
        assertNull(outcome.failed);
        assertEquals(3, outcome.steps.size());
        assertNotNull(outcome.steps.get(0).plan);
        assertEquals("remove " + UnitNaming.envPath(spec, target()),
                outcome.steps.get(0).plan.description);
        assertEquals(notStopped, outcome.steps.get(0).message);
        for (List<String> call : runner.calls()) {
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

        DestroyOutcome outcome = run(spec, runner, false);

        String warning = "unit path is not a regular file, skipped: " + unit;
        assertNull(outcome.failed);
        assertNull(outcome.steps.get(0).plan);
        assertEquals(warning, outcome.steps.get(0).message);
        assertEquals(warning, outcome.warnings.get(0));
        for (List<String> call : runner.calls()) {
            assertFalse(call.contains("stop"), call.toString());
            assertFalse(call.contains("disable"), call.toString());
        }
        assertTrue(Files.isSymbolicLink(unit));
    }

    @Test
    void dryRunPerformsNothingAndReportsSteps() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        DestroyOutcome outcome = run(spec, runner, true);

        assertNull(outcome.failed);
        assertEquals(6, outcome.steps.size());
        for (StepResult step : outcome.steps) {
            assertNotNull(step.plan);
            assertTrue(step.ok);
            assertNull(step.message);
        }
        assertEquals(Arrays.asList(
                "systemctl stop xac.demo.service",
                "systemctl disable xac.demo.service",
                "remove " + UnitNaming.unitPath(spec, target()),
                "remove " + UnitNaming.envPath(spec, target()),
                "systemctl daemon-reload",
                "remove path-links: []"), descriptions(outcome));
        assertTrue(runner.calls().isEmpty());
        assertTrue(outcome.warnings.isEmpty());
        assertTrue(Files.exists(UnitNaming.unitPath(spec, target())));
        assertTrue(Files.exists(UnitNaming.envPath(spec, target())));
    }

    @Test
    void removesLinkOnlyWhenItPointsToSource() throws IOException {
        Assumptions.assumeTrue(File.separatorChar == '/');
        Assumptions.assumeTrue(supportsSymlinks(root));
        Files.createDirectories(target().unitDir);
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);

        ServiceSpec spec = spec(source + ":" + linked);

        DestroyOutcome outcome = run(spec, new RecordingRunner(), false);

        assertNull(outcome.failed);
        assertFalse(Files.exists(linked));
        assertTrue(Files.isDirectory(source));
    }

    @Test
    void keepsEachWarningOnItsOwnStepWhenSeveralAccumulate() throws IOException {
        ServiceSpec spec = spec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());
        Files.write(unit, "Description=foreign\n".getBytes(StandardCharsets.UTF_8));
        Files.write(env, "BAR=b\n".getBytes(StandardCharsets.UTF_8));

        DestroyOutcome outcome = run(spec, new RecordingRunner(), false);

        String notStopped = "unit with same name is not managed by xac, not stopped; "
                + "stop it manually if a process is still running: " + unit;
        String foreignEnv = "existing env file is not managed by xac, refusing to remove, skipped: " + env;
        assertNull(outcome.failed);
        assertEquals(5, outcome.steps.size());
        assertEquals("unit is not managed by xac, skipped stop/disable/remove: " + unit,
                outcome.steps.get(0).message);
        assertEquals("remove " + env, outcome.steps.get(1).plan.description);
        assertEquals(notStopped, outcome.steps.get(1).message);
        assertNull(outcome.steps.get(2).plan);
        assertEquals(foreignEnv, outcome.steps.get(2).message);
        assertTrue(Files.exists(env));
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
