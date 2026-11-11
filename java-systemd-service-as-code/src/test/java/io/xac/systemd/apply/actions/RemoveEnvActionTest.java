package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ApplyContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoveEnvActionTest {

    @TempDir
    Path root;

    private ApplyContext context() {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        return new ApplyContext(spec, new SystemdTarget(root.resolve("units"), root.resolve("env")),
                new RecordingRunner(), false);
    }

    private Path envPath(ApplyContext ctx) {
        return UnitNaming.envPath(ctx.spec, ctx.target);
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    @Test
    void deletesManagedEnvFile() throws IOException {
        ApplyContext ctx = context();
        Path env = write(envPath(ctx), UnitRenderer.HEADER + "\nA=1\n");

        new RemoveEnvAction().run(ctx);

        assertFalse(Files.exists(env));
        assertTrue(ctx.warnings.isEmpty(), ctx.warnings.toString());
    }

    /** 风险 2：没有管理头的 env 文件不属于 xac，跳过并警告，绝不删除。 */
    @Test
    void skipsForeignEnvFileWithWarning() throws IOException {
        ApplyContext ctx = context();
        Path env = write(envPath(ctx), "SECRET=1\n");

        new RemoveEnvAction().run(ctx);

        assertTrue(Files.isRegularFile(env));
        assertEquals("SECRET=1\n",
                new String(Files.readAllBytes(env), StandardCharsets.UTF_8));
        assertEquals(1, ctx.warnings.size(), ctx.warnings.toString());
        assertTrue(ctx.warnings.get(0).contains("已存在非 xac 管理的 env 文件，拒绝删除，已跳过"),
                ctx.warnings.get(0));
        assertTrue(ctx.warnings.get(0).contains(env.toString()), ctx.warnings.get(0));
    }
}
