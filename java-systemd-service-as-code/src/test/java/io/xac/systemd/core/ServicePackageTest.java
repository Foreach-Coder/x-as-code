package io.xac.systemd.core;

import io.xac.systemd.apply.ApplyOutcome;
import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.StepPlan;
import io.xac.systemd.apply.StepResult;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.logs.FileLogStream;
import io.xac.systemd.logs.JournaldLogStream;
import io.xac.systemd.logs.LogSourceException;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面契约：open 只做加载 + 静态校验；预检只在 apply / destroy 里做；
 * status 不要求 root；plan 不执行任何动作。
 */
class ServicePackageTest {

    @TempDir
    Path pkg;

    private final RecordingRunner runner = new RecordingRunner();

    private static final class FakeProbe implements EnvironmentProbe {
        boolean root = true;

        @Override
        public boolean isRoot() {
            return root;
        }

        @Override
        public boolean systemdRunning() {
            return true;
        }

        @Override
        public boolean fileReadable(Path path) {
            return Files.isRegularFile(path);
        }

        @Override
        public boolean executable(Path path) {
            return true;
        }
    }

    private final FakeProbe probe = new FakeProbe();

    private SystemdTarget target() {
        return new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
    }

    private ServicePackage open() throws InvalidSpecException {
        return ServicePackage.open(pkg, runner, probe, target());
    }

    private void write(String... lines) throws java.io.IOException {
        Files.write(pkg.resolve("xac.yaml"),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private void writePlainSpec() throws java.io.IOException {
        write("name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
    }

    private void writeSpecWithMissingLinkSource() throws java.io.IOException {
        write("name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "path-links:",
                "  - missing:/opt/xac-target",
                "");
    }

    private void writeSpecWithExistingLinkTarget(Path existingTarget) throws java.io.IOException {
        write("name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "path-links:",
                "  - ./payload:" + existingTarget,
                "");
    }

    private static ExecResult show(String stdout) {
        return new ExecResult(0, stdout, "");
    }

    private static boolean hasPath(List<Violation> violations, String path) {
        for (Violation violation : violations) {
            if (path.equals(violation.path)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void openRejectsInvalidYamlWithoutTouchingSystemd() throws Exception {
        write("name: [");

        InvalidSpecException failure = assertThrows(InvalidSpecException.class, this::open);

        assertFalse(failure.violations.isEmpty());
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void openRunsStaticValidation() throws Exception {
        write("name: xac.demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "");

        InvalidSpecException failure = assertThrows(InvalidSpecException.class, this::open);

        assertEquals("name", failure.violations.get(0).path);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    /** open 不要求 artifact.jar 存在，预检留给 apply / destroy。 */
    @Test
    void openDoesNotRunPreflight() throws Exception {
        writePlainSpec();

        ServicePackage opened = open();

        assertEquals("demo", opened.spec().name);
        assertEquals(pkg.toAbsolutePath().normalize(), opened.packageDir());
        assertTrue(opened.isRoot());
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void planDescribesSevenStepsAndRunsNothing() throws Exception {
        writePlainSpec();
        ServicePackage opened = open();

        List<StepPlan> steps = opened.plan();

        Path unitPath = UnitNaming.unitPath(opened.spec(), target());
        Path envPath = UnitNaming.envPath(opened.spec(), target());
        assertEquals(7, steps.size());
        assertEquals("create path-links: []", steps.get(0).description);
        assertEquals("skip workdir (not configured)", steps.get(1).description);
        assertEquals("remove " + envPath + " (env is empty)", steps.get(2).description);
        assertEquals("write " + unitPath + " (0644)", steps.get(3).description);
        assertEquals("systemctl daemon-reload", steps.get(4).description);
        assertEquals("systemctl enable xac.demo.service", steps.get(5).description);
        assertEquals("systemctl restart xac.demo.service", steps.get(6).description);
        assertFalse(steps.get(0).destructive);
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void applyDryRunQueriesTheShadowingUnitOnlyAndWritesNothing() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        runner.addResultForCommand("systemctl", show("LoadState=not-found\nFragmentPath=\n"));
        ServicePackage opened = open();

        ApplyOutcome outcome = opened.apply(true);

        assertFalse(outcome.performed);
        assertEquals(7, outcome.steps.size());
        assertEquals(1, runner.calls().size(), runner.calls().toString());
        assertFalse(Files.exists(UnitNaming.unitPath(opened.spec(), target())));
        assertFalse(Files.exists(UnitNaming.envPath(opened.spec(), target())));
    }

    @Test
    void applyRequiresRoot() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        probe.root = false;
        ServicePackage opened = open();

        InvalidSpecException failure = assertThrows(InvalidSpecException.class,
                () -> opened.apply(false));

        assertEquals(1, failure.violations.size(), failure.violations.toString());
        assertEquals("", failure.violations.get(0).path);
        assertEquals("apply / destroy require root", failure.violations.get(0).message);
        assertFalse(Files.exists(UnitNaming.unitPath(opened.spec(), target())));
    }

    @Test
    void statusDoesNotRequireRoot() throws Exception {
        writePlainSpec();
        ServicePackage opened = open();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        Files.write(UnitNaming.unitPath(opened.spec(), target()),
                new UnitRenderer().render(opened.spec(), target()).getBytes(StandardCharsets.UTF_8));
        runner.addResultForCommand("systemctl", show(String.join("\n",
                "LoadState=loaded",
                "ActiveState=active",
                "SubState=running",
                "UnitFileState=enabled",
                "MainPID=1234",
                "ActiveEnterTimestamp=Mon 2026-11-10 19:00:00 CST",
                "FragmentPath=" + UnitNaming.unitPath(opened.spec(), target()),
                "")));
        probe.root = false;

        StatusReport report = opened.status();

        assertFalse(opened.isRoot());
        assertTrue(report.healthy, report.drift.toString());
        assertTrue(report.drift.isEmpty(), report.drift.toString());
        assertEquals("active", report.status.show.get("ActiveState"));
    }

    @Test
    void openAcceptsMissingPathLinkSourceButApplyRejectsIt() throws Exception {
        writeSpecWithMissingLinkSource();
        ServicePackage opened = open();
        assertEquals("demo", opened.spec().name);

        Files.createFile(pkg.resolve("app.jar"));
        InvalidSpecException failure = assertThrows(InvalidSpecException.class,
                () -> opened.apply(true));

        assertTrue(hasPath(failure.violations, "path-links[0]"),
                failure.violations.toString());
    }

    @Test
    void destroyPlanDoesNotRequireArtifactJar() throws Exception {
        writePlainSpec();
        ServicePackage opened = open();

        DestroyOutcome plan = opened.destroyPlan();

        assertTrue(plan.willRemove.isEmpty(), plan.willRemove.toString());
        assertEquals(3, plan.willKeep.size(), plan.willKeep.toString());
        assertTrue(plan.steps.isEmpty(), plan.steps.toString());
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
    }

    @Test
    void destroyDryRunKeepsFilesAndDestroyRemovesThem() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        runner.addResultForCommand("systemctl", show("LoadState=not-found\nFragmentPath=\n"));
        ServicePackage opened = open();
        opened.apply(false);
        Path unitPath = UnitNaming.unitPath(opened.spec(), target());
        Path envPath = UnitNaming.envPath(opened.spec(), target());
        assertTrue(Files.isRegularFile(unitPath));

        DestroyOutcome dryRun = opened.destroy(true);

        assertNull(dryRun.failed);
        assertFalse(dryRun.steps.isEmpty());
        assertTrue(Files.isRegularFile(unitPath));
        assertFalse(Files.exists(envPath));

        DestroyOutcome performed = opened.destroy(false);

        assertNull(performed.failed);
        assertFalse(Files.exists(unitPath));
        assertEquals(1, performed.willRemove.size(), performed.willRemove.toString());
    }

    @Test
    void openAcceptsExistingPathLinkTarget() throws Exception {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/',
                "path-links 目标只支持 POSIX 绝对路径");
        Path existingTarget = pkg.resolve("srv");
        Files.createDirectories(existingTarget);
        writeSpecWithExistingLinkTarget(existingTarget);

        ServicePackage opened = open();

        assertEquals("demo", opened.spec().name);
    }

    @Test
    void destroyPlanAcceptsExistingPathLinkTarget() throws Exception {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/',
                "path-links 目标只支持 POSIX 绝对路径");
        Path existingTarget = pkg.resolve("srv");
        Files.createDirectories(existingTarget);
        writeSpecWithExistingLinkTarget(existingTarget);

        DestroyOutcome plan = open().destroyPlan();

        assertTrue(plan.willRemove.isEmpty(), plan.willRemove.toString());
        assertNull(plan.failed);
    }

    @Test
    void logsRejectNonPositiveLineCount() throws Exception {
        writePlainSpec();
        ServicePackage opened = open();

        LogSourceException failure = assertThrows(LogSourceException.class,
                () -> opened.logs(0));

        assertEquals("-n must be a positive integer", failure.getMessage());
        assertTrue(runner.startedCalls().isEmpty(), runner.startedCalls().toString());
    }

    @Test
    void logsDefaultToJournald() throws Exception {
        writePlainSpec();
        ServicePackage opened = open();

        LogStream stream = opened.logs(10);

        assertTrue(stream instanceof JournaldLogStream, stream.getClass().getName());
        assertFalse(stream.isFollow(), "logs(int) must be non-follow");
        LogStream following = opened.logs(10, true);
        assertTrue(following.isFollow(), "logs(int, true) must follow");
        stream.close();
        following.close();
    }

    @Test
    void logsUseFileStreamWhenConfigured() throws Exception {
        Path log = pkg.resolve("app.log");
        Files.write(log, "a\n".getBytes(StandardCharsets.UTF_8));
        write("name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "logs:",
                "  file: " + log.toString().replace("\\", "/"),
                "");
        ServicePackage opened = open();

        LogStream stream = opened.logs(10);

        assertTrue(stream instanceof FileLogStream, stream.getClass().getName());
        assertFalse(stream.isFollow(), "logs(int) must be non-follow");
        stream.close();
    }

    @Test
    void restartRunsSystemctlRestartWithoutWritingUnit() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        runner.addResultForCommand("systemctl", show("LoadState=not-found\nFragmentPath=\n"));
        ServicePackage opened = open();

        StepResult result = opened.restart();

        assertTrue(result.ok, result.message);
        assertNull(result.message);
        assertEquals("systemctl restart xac.demo.service", result.plan.description);
        assertFalse(Files.exists(UnitNaming.unitPath(opened.spec(), target())),
                "restart must not write the unit");
        assertFalse(Files.exists(UnitNaming.envPath(opened.spec(), target())),
                "restart must not write the env file");
        assertEquals(2, runner.calls().size(), runner.calls().toString());
        assertEquals(Arrays.asList("systemctl", "restart", "xac.demo.service"),
                runner.calls().get(1));
    }

    @Test
    void restartRequiresRoot() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        probe.root = false;
        ServicePackage opened = open();

        InvalidSpecException failure = assertThrows(InvalidSpecException.class, opened::restart);

        assertEquals("apply / destroy require root", failure.violations.get(0).message);
        assertFalse(hasRestartCall(), "non-root must not touch the service: " + runner.calls());
    }

    private boolean hasRestartCall() {
        for (List<String> call : runner.calls()) {
            if (call.size() >= 2 && "systemctl".equals(call.get(0))
                    && "restart".equals(call.get(1))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void restartReportsSystemctlFailure() throws Exception {
        Files.createFile(pkg.resolve("app.jar"));
        writePlainSpec();
        runner.addResultForCommand("systemctl", show("LoadState=not-found\nFragmentPath=\n"));
        runner.addResult(Arrays.asList("systemctl", "restart", "xac.demo.service"),
                new ExecResult(1, "", "unit not found"));
        ServicePackage opened = open();

        StepResult result = opened.restart();

        assertFalse(result.ok);
        assertTrue(result.message.contains("exit 1"), result.message);
        assertTrue(result.message.contains("unit not found"), result.message);
        assertFalse(Files.exists(UnitNaming.unitPath(opened.spec(), target())),
                "failed restart must not write the unit");
    }
}
