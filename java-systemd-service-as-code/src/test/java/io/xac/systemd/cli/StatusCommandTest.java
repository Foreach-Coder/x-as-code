package io.xac.systemd.cli;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * status 的 stdout/stderr/退出码是 CLI 契约：这里逐字节比对整段输出。
 * 健康与漂移各锁一个场景；status 不要求 root。
 */
class StatusCommandTest {

    @TempDir
    Path pkg;

    private static final class FakeProbe implements EnvironmentProbe {
        @Override
        public boolean isRoot() {
            return false;
        }

        @Override
        public boolean systemdRunning() {
            return false;
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

    private SystemdTarget target() {
        return new SystemdTarget(pkg.resolve("units"), pkg.resolve("env"));
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
        Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private ServiceSpec spec() {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .build();
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

    private Captured capture(RecordingRunner runner) throws IOException {
        writeSpec();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int code = StatusCommand.run(pkg, new FakeProbe(), runner, target(),
                new PrintStream(outBuffer, true, "UTF-8"),
                new PrintStream(errBuffer, true, "UTF-8"));
        return new Captured(code, outBuffer.toString("UTF-8"), errBuffer.toString("UTF-8"));
    }

    private static RecordingRunner runnerShowing(String stdout) {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, stdout, ""));
        return runner;
    }

    /** 红线：健康状态的完整输出与退出码固定。 */
    @Test
    void healthyStatusPrintsExactOutputAndExitsZero() throws IOException {
        ServiceSpec spec = spec();
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        Path unitPath = UnitNaming.unitPath(spec, target);
        Files.write(unitPath, new UnitRenderer().render(spec, target)
                .getBytes(StandardCharsets.UTF_8));
        RecordingRunner runner = runnerShowing(String.join("\n",
                "LoadState=loaded",
                "ActiveState=active",
                "SubState=running",
                "UnitFileState=enabled",
                "MainPID=1234",
                "ActiveEnterTimestamp=Mon 2026-11-10 19:00:00 CST",
                "FragmentPath=" + unitPath,
                ""));

        Captured captured = capture(runner);

        String expected = String.join("\n",
                "service: demo",
                "package: " + pkg.toAbsolutePath().normalize(),
                "unit: " + unitPath + " (in sync)",
                "env: (not configured)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "");
        assertEquals(0, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
    }

    /** 红线：漂移状态的完整输出与退出码固定。 */
    @Test
    void driftStatusPrintsExactOutputAndExitsOne() throws IOException {
        ServiceSpec spec = spec();
        Path unitPath = UnitNaming.unitPath(spec, target());
        RecordingRunner runner = runnerShowing(String.join("\n",
                "LoadState=loaded",
                "ActiveState=inactive",
                "SubState=dead",
                "UnitFileState=disabled",
                "MainPID=0",
                "ActiveEnterTimestamp=",
                "FragmentPath=",
                ""));

        Captured captured = capture(runner);

        String expected = String.join("\n",
                "service: demo",
                "package: " + pkg.toAbsolutePath().normalize(),
                "unit: " + unitPath + " (missing)",
                "env: (not configured)",
                "active: inactive (dead)",
                "pid: 0",
                "auto-start: disabled",
                "drift: unit not installed: " + unitPath,
                "drift: service: inactive (dead)",
                "drift: auto-start disabled",
                "");
        assertEquals(1, captured.code);
        assertEquals(expected, captured.out);
        assertEquals("", captured.err);
    }
}
