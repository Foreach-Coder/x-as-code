package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitFileActionTest {

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
    void overwritesManagedUnit() throws IOException {
        ApplyContext ctx = context();
        Path unit = unitPath(ctx);
        Files.createDirectories(unit.getParent());
        Files.write(unit, (UnitRenderer.HEADER + "\nold\n").getBytes(StandardCharsets.UTF_8));

        new UnitFileAction().run(ctx);

        assertEquals(ctx.unitContent, new String(Files.readAllBytes(unit), StandardCharsets.UTF_8));
    }

    @Test
    void refusesUnmanagedUnit() throws IOException {
        ApplyContext ctx = context();
        Path unit = unitPath(ctx);
        Files.createDirectories(unit.getParent());
        Files.write(unit, "Description=someone else\n".getBytes(StandardCharsets.UTF_8));

        ActionFailure failure = assertThrows(ActionFailure.class, () -> new UnitFileAction().run(ctx));

        assertTrue(failure.getMessage().contains("existing unit file is not managed by xac"),
                failure.getMessage());
        assertEquals("Description=someone else\n",
                new String(Files.readAllBytes(unit), StandardCharsets.UTF_8));
    }

    @Test
    void acceptsBomBeforeHeader() throws IOException {
        ApplyContext ctx = context();
        Path unit = unitPath(ctx);
        Files.createDirectories(unit.getParent());
        Files.write(unit, ("\uFEFF" + UnitRenderer.HEADER + "\nold\n").getBytes(StandardCharsets.UTF_8));

        new UnitFileAction().run(ctx);

        assertEquals(ctx.unitContent, new String(Files.readAllBytes(unit), StandardCharsets.UTF_8));
    }
}
