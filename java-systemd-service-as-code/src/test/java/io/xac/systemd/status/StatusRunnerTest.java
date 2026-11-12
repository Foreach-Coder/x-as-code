package io.xac.systemd.status;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
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

class StatusRunnerTest {

    @TempDir
    Path root;

    private static final String SHOW = String.join("\n",
            "LoadState=loaded",
            "ActiveState=active",
            "SubState=running",
            "UnitFileState=enabled",
            "MainPID=1234",
            "ActiveEnterTimestamp=Mon 2026-11-10 19:00:00 CST",
            "FragmentPath=/etc/systemd/system/xac.demo.service",
            "");

    private static final String SHOW_INACTIVE = String.join("\n",
            "LoadState=loaded",
            "ActiveState=inactive",
            "SubState=dead",
            "UnitFileState=enabled",
            "MainPID=0",
            "ActiveEnterTimestamp=",
            "FragmentPath=/etc/systemd/system/xac.demo.service",
            "");

    private static final String SHOW_NO_ACTIVE = String.join("\n",
            "LoadState=loaded",
            "ActiveState=",
            "SubState=dead",
            "UnitFileState=enabled",
            "MainPID=0",
            "ActiveEnterTimestamp=",
            "FragmentPath=/etc/systemd/system/xac.demo.service",
            "");

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(Map<String, String> env) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private static Map<String, String> env() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("BAR", "b");
        return map;
    }

    private RecordingRunner runnerWithShow() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW, ""));
        return runner;
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

    @Test
    void reportsHealthyAfterApplyState() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        assertTrue(result.healthy(), result.drift().toString());
        assertEquals(0, result.drift().size(), result.drift().toString());
    }

    @Test
    void detectsOutOfSyncUnitAndEnv() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        Files.write(UnitNaming.unitPath(spec, target),
                "old".getBytes(StandardCharsets.UTF_8));

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        assertFalse(result.healthy());
        assertTrue(result.drift().get(0).contains("unit"), result.drift().toString());
        assertTrue(result.drift().contains("env missing: " + UnitNaming.envPath(spec, target)),
                result.drift().toString());
    }

    @Test
    void treatsMissingUnitAsDrift() {
        ServiceSpec spec = spec(Collections.<String, String>emptyMap());
        StatusResult result = new StatusRunner().run(spec, target(), runnerWithShow());
        assertFalse(result.healthy());
        assertTrue(result.drift().contains("unit not installed: "
                + UnitNaming.unitPath(spec, target())), result.drift().toString());
    }

    @Test
    void treatsUnreadableEnvAsNotDrift() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        final Path envPath = UnitNaming.envPath(spec, target);
        Files.write(envPath, renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        StatusRunner runner = new StatusRunner(new StatusRunner.ContentReader() {
            @Override
            public String read(Path path) throws IOException {
                if (path.equals(envPath)) {
                    throw new AccessDeniedException(path.toString());
                }
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        });

        StatusResult result = runner.run(spec, target, runnerWithShow());

        assertTrue(result.healthy(), result.drift().toString());
        for (String line : result.drift()) {
            assertFalse(line.contains("env"), result.drift().toString());
        }
    }

    /** 设计文档 §6.3：exit 0 还要求服务在运行；inactive 必须让 status 以 1 退出。 */
    @Test
    void reportsInactiveServiceAsDrift() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        install(spec, target);
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW_INACTIVE, ""));

        StatusResult result = new StatusRunner().run(spec, target, runner);

        assertFalse(result.healthy(), result.drift().toString());
        assertTrue(result.drift().contains("service: inactive (dead)"), result.drift().toString());
        // StatusCommand 把 !healthy() 映射为退出码 1。
        assertEquals(Arrays.asList("service: inactive (dead)"), result.drift());
    }

    /** ActiveState 为空表示没有观察到运行状态，不能据此宣称服务在运行或不在运行。 */
    @Test
    void doesNotDriftWhenActiveStateWasNotObserved() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        install(spec, target);
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW_NO_ACTIVE, ""));

        StatusResult result = new StatusRunner().run(spec, target, runner);

        assertTrue(result.healthy(), result.drift().toString());
        assertEquals(0, result.drift().size(), result.drift().toString());
    }

    /** 查询失败不能被读成「未启用」；只报告没有观察到状态。 */
    @Test
    void reportsUnqueryableStateAsDrift() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        install(spec, target);
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl",
                new ExecResult(1, "", "Failed to connect to bus: No such file or directory"));

        StatusResult result = new StatusRunner().run(spec, target, runner);

        assertFalse(result.healthy(), result.drift().toString());
        assertTrue(result.drift().contains("systemd: cannot query xac.demo.service"),
                result.drift().toString());
        assertFalse(result.drift().contains("auto-start disabled"), result.drift().toString());
    }
}
