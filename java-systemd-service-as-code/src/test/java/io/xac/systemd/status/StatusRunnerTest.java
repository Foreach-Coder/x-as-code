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
            "FragmentPath=/etc/systemd/system/demo.service",
            "");

    private static final String SHOW_INACTIVE = String.join("\n",
            "LoadState=loaded",
            "ActiveState=inactive",
            "SubState=dead",
            "UnitFileState=enabled",
            "MainPID=0",
            "ActiveEnterTimestamp=",
            "FragmentPath=/etc/systemd/system/demo.service",
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

        assertTrue(result.healthy(), result.format());
        assertEquals(0, result.drift().size(), result.format());
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
        assertTrue(result.drift().contains("env 文件不存在: " + UnitNaming.envPath(spec, target)),
                result.drift().toString());
    }

    @Test
    void formatsReport() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        String expected = String.join("\n",
                "service: demo",
                "package: " + root.toAbsolutePath().normalize(),
                "unit: " + UnitNaming.unitPath(spec, target) + " (in sync)",
                "env: " + UnitNaming.envPath(spec, target) + " (in sync)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "");

        assertEquals(expected,
                new StatusRunner().run(spec, target, runnerWithShow()).format());
    }

    @Test
    void treatsMissingUnitAsDrift() {
        ServiceSpec spec = spec(Collections.<String, String>emptyMap());
        StatusResult result = new StatusRunner().run(spec, target(), runnerWithShow());
        assertFalse(result.healthy());
        assertTrue(result.format().contains("(missing)"), result.format());
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

        assertTrue(result.healthy(), result.format());
        for (String line : result.drift()) {
            assertFalse(line.contains("env"), result.drift().toString());
        }
        assertTrue(result.format().contains("env: (无法校验：需要 root 权限)"), result.format());
        assertFalse(result.format().contains("out of sync"), result.format());
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

        assertFalse(result.healthy(), result.format());
        assertTrue(result.drift().contains("service: inactive (dead)"), result.drift().toString());
        // StatusCommand 把 !healthy() 映射为退出码 1。
        assertEquals(1, result.healthy() ? 0 : 1);
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

        assertFalse(result.healthy(), result.format());
        assertTrue(result.drift().contains("systemd: 无法查询 demo.service 的状态"),
                result.drift().toString());
        assertFalse(result.drift().contains("未设置开机自启"), result.drift().toString());
        assertTrue(result.format().contains("auto-start: unknown"), result.format());
    }
}
