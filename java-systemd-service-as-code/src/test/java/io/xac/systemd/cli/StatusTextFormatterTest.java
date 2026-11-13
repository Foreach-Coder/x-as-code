package io.xac.systemd.cli;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.status.StatusRunner;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * status 的文本输出是 CLI 契约：这里断言整段输出逐字节不变。
 * 原先是 {@code StatusResult.format()} 的测试，重构后渲染搬到 cli 层。
 */
class StatusTextFormatterTest {

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

    private ServiceSpec spec(Path packageDir, Map<String, String> env) {
        return ServiceSpec.builder(packageDir)
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

    private static RecordingRunner runnerWithShow() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW, ""));
        return runner;
    }

    private static Map<String, String> show() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : SHOW.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    private static StatusResult direct(Path packageDir, boolean unitInstalled, boolean unitInSync,
                                       boolean envConfigured, boolean envInstalled, boolean envReadable,
                                       boolean envInSync, Map<String, String> show, boolean queryOk,
                                       boolean enabled) {
        return new StatusResult("demo", packageDir,
                packageDir.resolve("units").resolve("xac.demo.service"),
                unitInstalled, unitInSync,
                packageDir.resolve("env").resolve("xac.demo.env"),
                envConfigured, envInstalled, envReadable, envInSync, show, queryOk, enabled, true,
                new ArrayList<String>());
    }

    private static String render(StatusResult result) {
        return StatusTextFormatter.format(result);
    }

    @Test
    void formatsHealthyReport() throws IOException {
        Path packageDir = root.resolve("healthy");
        SystemdTarget target = new SystemdTarget(packageDir.resolve("units"), packageDir.resolve("env"));
        ServiceSpec spec = spec(packageDir, env());
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);
        Files.write(unitPath, renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(envPath, renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        String expected = String.join("\n",
                "service: demo",
                "package: " + packageDir.toAbsolutePath().normalize(),
                "unit: " + unitPath + " (in sync)",
                "env: " + envPath + " (in sync)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "");
        assertEquals(expected, render(result));
    }

    @Test
    void formatsMissingUnit() {
        Path packageDir = root.resolve("missing-unit");
        SystemdTarget target = new SystemdTarget(packageDir.resolve("units"), packageDir.resolve("env"));
        ServiceSpec spec = spec(packageDir, Collections.<String, String>emptyMap());

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        Path unitPath = UnitNaming.unitPath(spec, target);
        String expected = String.join("\n",
                "service: demo",
                "package: " + packageDir.toAbsolutePath().normalize(),
                "unit: " + unitPath + " (missing)",
                "env: (not configured)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "drift: unit not installed: " + unitPath,
                "");
        assertEquals(expected, render(result));
    }

    @Test
    void formatsUnreadableEnvAsCannotVerify() {
        Path packageDir = root.resolve("unreadable-env");
        StatusResult result = direct(packageDir, true, true, true, true, false, false,
                show(), true, true);

        String expected = String.join("\n",
                "service: demo",
                "package: " + packageDir.toAbsolutePath().normalize(),
                "unit: " + packageDir.resolve("units").resolve("xac.demo.service") + " (in sync)",
                "env: (cannot verify: root required)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "");
        assertEquals(expected, render(result));
    }

    /** unit missing 与 env 不可读是两回事：前者渲染 (missing)，后者渲染 (cannot verify: root required)。 */
    @Test
    void keepsMissingAndUnreadableEnvDistinct() {
        Path packageDir = root.resolve("missing-and-unreadable");
        StatusResult result = direct(packageDir, false, false, true, true, false, false,
                show(), true, true);

        String report = render(result);
        assertTrue(report.contains(" (missing)"), report);
        assertTrue(report.contains("env: (cannot verify: root required)"), report);
        assertFalse(report.contains("env: (not configured)"), report);
        assertFalse(report.contains("out of sync"), report);
        String expected = String.join("\n",
                "service: demo",
                "package: " + packageDir.toAbsolutePath().normalize(),
                "unit: " + packageDir.resolve("units").resolve("xac.demo.service") + " (missing)",
                "env: (cannot verify: root required)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "drift: unit not installed: "
                        + packageDir.resolve("units").resolve("xac.demo.service"),
                "");
        assertEquals(expected, report);
    }

    @Test
    void formatsDriftWithUnqueryableSystemd() {
        Path packageDir = root.resolve("drift");
        StatusResult result = direct(packageDir, true, false, true, false, true, false,
                new LinkedHashMap<String, String>(), false, false);

        Path unitPath = packageDir.resolve("units").resolve("xac.demo.service");
        Path envPath = packageDir.resolve("env").resolve("xac.demo.env");
        String expected = String.join("\n",
                "service: demo",
                "package: " + packageDir.toAbsolutePath().normalize(),
                "unit: " + unitPath + " (out of sync)",
                "env: " + envPath + " (out of sync)",
                "active: unknown",
                "pid: unknown",
                "auto-start: unknown",
                "drift: unit out of sync: " + unitPath,
                "drift: env missing: " + envPath,
                "drift: systemd: cannot query xac.demo.service",
                "");
        assertEquals(expected, render(result));
    }
}
