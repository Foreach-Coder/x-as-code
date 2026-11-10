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

class RemoveUnitActionTest {

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

    private Path unitPath(ApplyContext ctx) {
        return UnitNaming.unitPath(ctx.spec, ctx.target);
    }

    @Test
    void removesManagedUnit() throws IOException {
        ApplyContext ctx = context();
        Path unit = unitPath(ctx);
        Files.createDirectories(unit.getParent());
        Files.write(unit, (UnitRenderer.HEADER + "\n[Unit]\n").getBytes(StandardCharsets.UTF_8));

        new RemoveUnitAction().run(ctx);

        assertFalse(Files.exists(unit));
        assertTrue(ctx.warnings.isEmpty(), ctx.warnings.toString());
    }

    @Test
    void skipsUnmanagedUnit() throws IOException {
        ApplyContext ctx = context();
        Path unit = unitPath(ctx);
        Files.createDirectories(unit.getParent());
        Files.write(unit, "Description=someone else\n".getBytes(StandardCharsets.UTF_8));

        new RemoveUnitAction().run(ctx);

        assertTrue(Files.exists(unit));
        assertEquals(Collections.singletonList("不是 xac 管理的 unit，已跳过删除: " + unit),
                ctx.warnings);
    }

    @Test
    void ignoresMissingUnit() {
        ApplyContext ctx = context();

        new RemoveUnitAction().run(ctx);

        assertTrue(ctx.warnings.isEmpty(), ctx.warnings.toString());
    }
}
