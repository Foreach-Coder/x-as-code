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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyRunnerTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(Boolean autoStart, Map<String, String> env) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(autoStart, null, null, null))
                .build();
    }

    private static Map<String, String> env(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private ApplyOutcome run(ApplyContext ctx) throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        return new ApplyRunner().run(ctx);
    }

    @Test
    void writesFilesThenReloadsEnablesAndRestarts() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        ApplyOutcome outcome = run(ctx);

        assertTrue(outcome.performed);
        assertNull(outcome.failed);
        assertEquals(7, outcome.steps.size());
        for (StepResult step : outcome.steps) {
            assertTrue(step.ok, step.plan.description);
            assertNull(step.message, step.plan.description);
            assertFalse(step.plan.destructive, step.plan.description);
        }
        assertEquals(ctx.unitContent, read(UnitNaming.unitPath(ctx.spec, ctx.target)));
        assertEquals(ctx.envContent, read(UnitNaming.envPath(ctx.spec, ctx.target)));
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "daemon-reload"),
                Arrays.asList("systemctl", "enable", "xac.demo.service"),
                Arrays.asList("systemctl", "restart", "xac.demo.service")), runner.calls());
    }

    @Test
    void disablesWhenAutoStartIsFalse() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(false, env("BAR", "b")), target(), runner, false);

        ApplyOutcome outcome = run(ctx);

        assertNull(outcome.failed);
        assertEquals(Arrays.asList("systemctl", "disable", "xac.demo.service"), runner.calls().get(1));
        assertFalse(ctx.unitContent.contains("[Install]"));
    }

    @Test
    void deletesEnvFileWhenEnvIsEmpty() throws IOException {
        Files.createDirectories(target().envDir);
        Path envFile = target().envDir.resolve("xac.demo.env");
        Files.write(envFile, (UnitRenderer.HEADER + "\nOLD=1\n").getBytes(StandardCharsets.UTF_8));

        ApplyContext ctx = new ApplyContext(spec(true, Collections.<String, String>emptyMap()),
                target(), new RecordingRunner(), false);
        ApplyOutcome outcome = run(ctx);

        assertNull(outcome.failed);
        assertFalse(Files.exists(envFile));
        assertFalse(ctx.unitContent.contains("EnvironmentFile="));
    }

    @Test
    void stopsAtFirstFailure() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(1, "", "boom"));
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        ApplyOutcome outcome = run(ctx);

        assertTrue(outcome.performed);
        assertNotNull(outcome.failed);
        assertFalse(outcome.failed.ok);
        assertTrue(outcome.failed.message.contains("boom"), outcome.failed.message);
        assertEquals(5, outcome.steps.size(), outcome.steps.toString());
        assertEquals(outcome.failed, outcome.steps.get(4));
        assertEquals(1, runner.calls().size(), runner.calls().toString());
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, true);

        ApplyOutcome outcome = new ApplyRunner().run(ctx);

        assertFalse(outcome.performed);
        assertNull(outcome.failed);
        assertTrue(runner.calls().isEmpty());
        assertFalse(Files.exists(target().unitDir.resolve("xac.demo.service")));
        assertEquals(7, outcome.steps.size(), outcome.steps.toString());
        for (StepResult step : outcome.steps) {
            assertTrue(step.ok);
            assertNull(step.message);
            assertFalse(step.plan.description.startsWith("would "), step.plan.description);
        }
        assertTrue(outcome.steps.get(0).plan.description.contains("path-links"));
        assertTrue(outcome.steps.get(6).plan.description.contains("restart"));
    }

    /** ok=true + message!=null：动作写出的警告合并进该步结果，取走后 ctx 清空。 */
    @Test
    void warningsBecomeTheStepMessage() {
        Action warns = new Action() {
            @Override
            public String describe(ApplyContext ctx) {
                return "warn step";
            }

            @Override
            public void run(ApplyContext ctx) {
                ctx.warnings.add("first warning");
                ctx.warnings.add("second warning");
            }
        };
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(),
                new RecordingRunner(), false);

        ApplyOutcome outcome = new ApplyRunner().run(ctx, Collections.singletonList(warns));

        assertEquals(1, outcome.steps.size(), outcome.steps.toString());
        assertTrue(outcome.steps.get(0).ok);
        assertEquals("first warning; second warning", outcome.steps.get(0).message);
        assertTrue(ctx.warnings.isEmpty(), ctx.warnings.toString());
        assertNull(outcome.failed);
    }

    /** 警告被取走后，下一步开始前 ctx.warnings 必须为空。 */
    @Test
    void warningsAreTakenBeforeTheNextStep() {
        Action warns = new Action() {
            @Override
            public String describe(ApplyContext ctx) {
                return "warn step";
            }

            @Override
            public void run(ApplyContext ctx) {
                ctx.warnings.add("only warning");
            }
        };
        final List<String> seenByNextStep = new ArrayList<String>();
        Action observes = new Action() {
            @Override
            public String describe(ApplyContext ctx) {
                return "observe step";
            }

            @Override
            public void run(ApplyContext ctx) {
                seenByNextStep.addAll(ctx.warnings);
            }
        };
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(),
                new RecordingRunner(), false);

        ApplyOutcome outcome = new ApplyRunner().run(ctx, Arrays.asList(warns, observes));

        assertTrue(seenByNextStep.isEmpty(), seenByNextStep.toString());
        assertEquals("only warning", outcome.steps.get(0).message);
        assertNull(outcome.steps.get(1).message);
        assertNull(outcome.failed);
    }

    @Test
    void refusesToDeleteForeignEnvFile() throws IOException {
        Files.createDirectories(target().envDir);
        Path envFile = target().envDir.resolve("xac.demo.env");
        Files.write(envFile, "OLD=1\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(target().unitDir);
        ApplyContext ctx = new ApplyContext(spec(true, Collections.<String, String>emptyMap()),
                target(), new RecordingRunner(), false);

        ApplyOutcome outcome = run(ctx);

        assertNotNull(outcome.failed);
        assertEquals(3, outcome.steps.size(), outcome.steps.toString());
        assertTrue(outcome.failed.message.contains("existing env file is not managed by xac"),
                outcome.failed.message);
        assertTrue(Files.isRegularFile(envFile));
        assertEquals("OLD=1\n", read(envFile));
        assertFalse(Files.exists(target().unitDir.resolve("xac.demo.service")));
    }

    @Test
    void refusesBlockedPathLinkBeforeDoingAnything() throws IOException {
        Assumptions.assumeTrue(File.separatorChar == '/', "path-links 目标只支持 POSIX 绝对路径");
        Path source = Files.createDirectories(root.resolve("payload"));
        Path link = Files.createDirectories(root.resolve("link"));
        Files.write(link.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(Collections.singletonList(source + ":" + link))
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec, target(), runner, false);
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);

        ApplyOutcome outcome = new ApplyRunner().run(ctx);

        assertNotNull(outcome.failed);
        assertEquals(1, outcome.steps.size(), outcome.steps.toString());
        assertTrue(outcome.failed.message
                        .contains("target exists and is not a symlink to source, handle it manually: "
                                + link),
                outcome.failed.message);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
        assertTrue(Files.isDirectory(link));
        assertEquals("keep", read(link.resolve("keep.txt")));
        assertFalse(Files.exists(target().unitDir.resolve("xac.demo.service")));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.bak*")) {
            assertFalse(entries.iterator().hasNext(), "不应创建 .bak 备份");
        }
    }
}
