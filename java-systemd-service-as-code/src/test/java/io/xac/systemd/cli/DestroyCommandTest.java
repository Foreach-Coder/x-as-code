package io.xac.systemd.cli;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.actions.DisableAction;
import io.xac.systemd.apply.actions.StopAction;
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
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyCommandTest {

    @TempDir
    Path root;

    private static final class FakeProbe implements EnvironmentProbe {
        @Override
        public boolean isRoot() { return true; }

        @Override
        public boolean systemdRunning() { return true; }

        @Override
        public boolean fileReadable(Path path) { return Files.isRegularFile(path); }

        @Override
        public boolean executable(Path path) { return true; }
    }

    private static final class Captured {
        final int code;
        final String out;
        final String err;

        Captured(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(String... links) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .pathLinks(new ArrayList<String>(Arrays.asList(links)))
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private void writeSpec() throws IOException {
        String yaml = String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
        Files.write(root.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static void install(ServiceSpec spec, SystemdTarget target) throws IOException {
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));
    }

    private Captured capture(boolean dryRun, boolean yes, RecordingRunner runner) throws IOException {
        return capture(dryRun, yes, runner, new DestroyRunner());
    }

    private Captured capture(boolean dryRun, boolean yes, RecordingRunner runner,
                             DestroyRunner destroyRunner) throws IOException {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int code = DestroyCommand.run(root, dryRun, yes, new FakeProbe(), runner, target(),
                new PrintStream(outBuffer, true, "UTF-8"),
                new PrintStream(errBuffer, true, "UTF-8"), destroyRunner);
        return new Captured(code, outBuffer.toString("UTF-8"), errBuffer.toString("UTF-8"));
    }

    private static String printPlan(ServiceSpec spec, SystemdTarget target) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DestroyCommand.printPlan(new DestroyRunner().plan(spec, target),
                new PrintStream(buffer, true, "UTF-8"));
        return buffer.toString("UTF-8");
    }

    private static String keepLine(ServiceSpec spec) {
        String pkg = spec.packageDir.toString();
        String workdir = spec.workdir == null ? pkg : spec.workdir.toString();
        return "keep: package directory " + pkg + ", workdir " + workdir + ", logs";
    }

    private static RecordingRunner unusedRunner() {
        return new RecordingRunner();
    }

    @Test
    void printsFilesToDeleteAndWhatSurvives() throws IOException {
        ServiceSpec spec = spec();
        SystemdTarget target = target();
        install(spec, target);

        String out = printPlan(spec, target);

        assertTrue(out.contains("will remove:"), out);
        assertTrue(out.contains(UnitNaming.unitPath(spec, target).toString()), out);
        assertTrue(out.contains(UnitNaming.envPath(spec, target).toString()), out);
        assertTrue(out.contains("keep: package directory " + root.toAbsolutePath().normalize()), out);
        assertTrue(out.contains(", logs"), out);
    }

    @Test
    void printsNoneWhenNothingIsInstalled() throws IOException {
        String out = printPlan(spec(), target());

        assertTrue(out.contains("will remove:"), out);
        assertTrue(out.contains("  (none)"), out);
    }

    @Test
    void leavesForeignUnitOutOfTheDeleteList() throws IOException {
        ServiceSpec spec = spec();
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.write(UnitNaming.unitPath(spec, target),
                "Description=foreign\n".getBytes(StandardCharsets.UTF_8));

        String out = printPlan(spec, target);

        assertTrue(out.contains("  (none)"), out);
        assertFalse(out.contains(UnitNaming.unitPath(spec, target).toString()), out);
    }

    @Test
    void printsWorkdirWhenConfigured() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .workdir("run")
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();

        String out = printPlan(spec, target());

        assertTrue(out.contains("workdir " + root.resolve("run").toAbsolutePath().normalize()), out);
    }

    @Test
    void listsOnlyLinksThatPointToTheirSource() throws IOException {
        Assumptions.assumeTrue(File.separatorChar == '/');
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);
        Path foreign = root.resolve("foreign");
        Files.createSymbolicLink(foreign, Files.createDirectories(root.resolve("other")));

        String out = printPlan(spec(source + ":" + linked, source + ":" + foreign), target());

        assertTrue(out.contains(linked.toAbsolutePath().normalize().toString()), out);
        assertFalse(out.contains(foreign.toAbsolutePath().normalize().toString()), out);
    }

    /** 红线：destroy 的 stdout/stderr/退出码全文固定，逐字节比对。 */
    @Test
    void destroyPrintsExactOutputAndRemovesFiles() throws IOException {
        ServiceSpec spec = spec();
        install(spec, target());
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());

        Captured captured = capture(false, true, unusedRunner());

        String expected = String.join(System.lineSeparator(),
                "will remove:",
                "  " + unit,
                "  " + env,
                keepLine(spec),
                "systemctl stop xac.demo.service",
                "systemctl disable xac.demo.service",
                "remove " + unit,
                "remove " + env,
                "systemctl daemon-reload",
                "remove path-links: []") + System.lineSeparator();

        assertEquals(0, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
        assertFalse(Files.exists(unit));
        assertFalse(Files.exists(env));
    }

    /** 红线：dry-run 的完整输出全文固定，且不做任何改动。 */
    @Test
    void dryRunPrintsExactOutput() throws IOException {
        ServiceSpec spec = spec();
        install(spec, target());
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());
        RecordingRunner runner = unusedRunner();

        Captured captured = capture(true, true, runner);

        String expected = String.join(System.lineSeparator(),
                "will remove:",
                "  " + unit,
                "  " + env,
                keepLine(spec),
                "would systemctl stop xac.demo.service",
                "would systemctl disable xac.demo.service",
                "would remove " + unit,
                "would remove " + env,
                "would systemctl daemon-reload",
                "would remove path-links: []") + System.lineSeparator();

        assertEquals(0, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
        assertTrue(runner.calls().isEmpty());
        assertTrue(Files.exists(unit));
        assertTrue(Files.exists(env));
    }

    /** 红线：非交互且没有 --yes 时只打印计划，错误信息进 stderr，退出 2。 */
    @Test
    void nonInteractiveWithoutYesPrintsPlanAndExitsTwo() throws IOException {
        Assumptions.assumeTrue(System.console() == null,
                "只在非交互的测试环境里验证此分支");
        ServiceSpec spec = spec();
        install(spec, target());
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());
        RecordingRunner runner = unusedRunner();

        Captured captured = capture(false, false, runner);

        String expected = String.join(System.lineSeparator(),
                "will remove:",
                "  " + unit,
                "  " + env,
                keepLine(spec)) + System.lineSeparator();

        assertEquals(2, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("use --yes in a non-interactive environment" + System.lineSeparator(),
                captured.err);
        assertTrue(runner.calls().isEmpty());
        assertTrue(Files.exists(unit));
        assertTrue(Files.exists(env));
    }

    /** 红线：同名 unit 不归 xac 管理时不得 stop/disable/删除，输出全文固定。 */
    @Test
    void foreignUnitPrintsExactOutputAndKeepsUnit() throws IOException {
        ServiceSpec spec = spec();
        install(spec, target());
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());
        Files.write(unit, "Description=foreign\n".getBytes(StandardCharsets.UTF_8));
        RecordingRunner runner = unusedRunner();

        Captured captured = capture(false, true, runner);

        String expected = String.join(System.lineSeparator(),
                "will remove:",
                "  " + env,
                keepLine(spec),
                "warning: unit is not managed by xac, skipped stop/disable/remove: " + unit,
                "remove " + env,
                "warning: unit with same name is not managed by xac, not stopped; "
                        + "stop it manually if a process is still running: " + unit,
                "systemctl daemon-reload",
                "remove path-links: []") + System.lineSeparator();

        assertEquals(0, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
        assertTrue(Files.exists(unit));
        assertFalse(Files.exists(env));
        for (List<String> call : runner.calls()) {
            assertFalse(call.contains("stop"), call.toString());
            assertFalse(call.contains("disable"), call.toString());
        }
    }

    /** 失败分支：第三步抛 ActionFailure 时，前两步已经打印，随后 failed: 并退出 1。 */
    @Test
    void failureOnThirdActionPrintsPriorStepsThenFailedAndExitsOne() throws IOException {
        ServiceSpec spec = spec();
        install(spec, target());
        Path unit = UnitNaming.unitPath(spec, target());
        Path env = UnitNaming.envPath(spec, target());
        RecordingRunner runner = unusedRunner();
        Action boom = new Action() {
            @Override
            public String describe(ApplyContext ctx) {
                return "explode";
            }

            @Override
            public void run(ApplyContext ctx) {
                throw new ActionFailure("boom");
            }
        };
        DestroyRunner destroyRunner = new DestroyRunner(Arrays.<Action>asList(
                new StopAction(), new DisableAction(), boom));

        Captured captured = capture(false, true, runner, destroyRunner);

        String expected = String.join(System.lineSeparator(),
                "will remove:",
                "  " + unit,
                "  " + env,
                keepLine(spec),
                "systemctl stop xac.demo.service",
                "systemctl disable xac.demo.service",
                "explode",
                "failed: boom") + System.lineSeparator();

        assertEquals(1, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
        assertTrue(Files.exists(unit));
        assertTrue(Files.exists(env));
        assertEquals(2, runner.calls().size(), runner.calls().toString());
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
