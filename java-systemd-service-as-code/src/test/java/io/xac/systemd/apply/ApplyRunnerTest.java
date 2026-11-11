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
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
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

    private String run(ApplyContext ctx) throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));
        assertEquals(0, code, buffer.toString("UTF-8"));
        return buffer.toString("UTF-8");
    }

    @Test
    void writesFilesThenReloadsEnablesAndRestarts() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        run(ctx);

        assertEquals(ctx.unitContent, read(UnitNaming.unitPath(ctx.spec, ctx.target)));
        assertEquals(ctx.envContent, read(UnitNaming.envPath(ctx.spec, ctx.target)));
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "daemon-reload"),
                Arrays.asList("systemctl", "enable", "demo.service"),
                Arrays.asList("systemctl", "restart", "demo.service")), runner.calls());
    }

    @Test
    void disablesWhenAutoStartIsFalse() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(false, env("BAR", "b")), target(), runner, false);

        run(ctx);

        assertEquals(Arrays.asList("systemctl", "disable", "demo.service"), runner.calls().get(1));
        assertFalse(ctx.unitContent.contains("[Install]"));
    }

    @Test
    void deletesEnvFileWhenEnvIsEmpty() throws IOException {
        Files.createDirectories(target().envDir);
        Path envFile = target().envDir.resolve("demo.env");
        Files.write(envFile, (UnitRenderer.HEADER + "\nOLD=1\n").getBytes(StandardCharsets.UTF_8));

        ApplyContext ctx = new ApplyContext(spec(true, Collections.<String, String>emptyMap()),
                target(), new RecordingRunner(), false);
        run(ctx);

        assertFalse(Files.exists(envFile));
        assertFalse(ctx.unitContent.contains("EnvironmentFile="));
    }

    @Test
    void stopsAtFirstFailure() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(1, "", "boom"));
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        Files.createDirectories(target().unitDir);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(1, code);
        assertEquals(1, runner.calls().size());
        assertTrue(buffer.toString("UTF-8").contains("boom"), buffer.toString("UTF-8"));
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, true);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(runner.calls().isEmpty());
        assertFalse(Files.exists(target().unitDir.resolve("demo.service")));
        List<String> lines = Arrays.asList(buffer.toString("UTF-8").split("\n"));
        assertEquals(7, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("would "), lines.toString());
        assertTrue(lines.get(0).contains("path-links"), lines.toString());
        assertTrue(lines.get(6).contains("restart"), lines.toString());
    }

    @Test
    void refusesToDeleteForeignEnvFile() throws IOException {
        Files.createDirectories(target().envDir);
        Path envFile = target().envDir.resolve("demo.env");
        Files.write(envFile, "OLD=1\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(target().unitDir);
        ApplyContext ctx = new ApplyContext(spec(true, Collections.<String, String>emptyMap()),
                target(), new RecordingRunner(), false);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(1, code, buffer.toString("UTF-8"));
        assertTrue(buffer.toString("UTF-8").contains("已存在非 xac 管理的 env 文件"),
                buffer.toString("UTF-8"));
        assertTrue(Files.isRegularFile(envFile));
        assertEquals("OLD=1\n", read(envFile));
        assertFalse(Files.exists(target().unitDir.resolve("demo.service")));
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

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(1, code);
        assertTrue(buffer.toString("UTF-8")
                        .contains("目标已存在且不是指向源的软链接，请人工处理: " + link),
                buffer.toString("UTF-8"));
        assertTrue(runner.calls().isEmpty(), runner.calls().toString());
        assertTrue(Files.isDirectory(link));
        assertEquals("keep", read(link.resolve("keep.txt")));
        assertFalse(Files.exists(target().unitDir.resolve("demo.service")));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.bak*")) {
            assertFalse(entries.iterator().hasNext(), "不应创建 .bak 备份");
        }
    }
}
