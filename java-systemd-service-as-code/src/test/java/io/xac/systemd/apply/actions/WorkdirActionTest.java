package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkdirActionTest {

    @TempDir
    Path root;

    private ApplyContext context(String workdir) {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .workdir(workdir)
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        return new ApplyContext(spec, new SystemdTarget(root.resolve("units"), root.resolve("env")),
                new RecordingRunner(), false);
    }

    @Test
    void createsLastSegmentOnly() throws IOException {
        Files.createDirectories(root.resolve("run"));
        new WorkdirAction().run(context("run/work"));
        assertTrue(Files.isDirectory(root.resolve("run/work")));
    }

    @Test
    void requiresParentToExist() {
        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new WorkdirAction().run(context("missing/work")));
        assertTrue(failure.getMessage().contains("父目录"), failure.getMessage());
    }

    @Test
    void skipsWhenWorkdirIsNotConfigured() {
        new WorkdirAction().run(context(null));
    }
}
