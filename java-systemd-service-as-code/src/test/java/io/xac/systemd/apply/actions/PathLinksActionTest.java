package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathLinksActionTest {

    @TempDir
    Path root;

    private ApplyContext context(String linkSpec) {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(new ArrayList<String>(Arrays.asList(linkSpec)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        SystemdTarget target = new SystemdTarget(root.resolve("units"), root.resolve("env"));
        return new ApplyContext(spec, target, new RecordingRunner(), false);
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("probe-link");
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void createsLinkWhenTargetMissing() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");

        new PathLinksAction().run(context(source + ":" + target));

        assertTrue(Files.isSymbolicLink(target));
        assertEquals(source, Files.readSymbolicLink(target));
    }

    @Test
    void isIdempotentWhenLinkAlreadyPointsToSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");
        Files.createSymbolicLink(target, source);

        new PathLinksAction().run(context(source + ":" + target));

        assertEquals(source, Files.readSymbolicLink(target));
        assertTrue(Files.notExists(root.resolve("link.txt.bak")));
    }

    @Test
    void backsUpRealDirectoryBeforeLinking() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path target = Files.createDirectories(root.resolve("link"));
        Files.write(target.resolve("old.txt"), "old".getBytes(StandardCharsets.UTF_8));

        new PathLinksAction().run(context(source + ":" + target));

        assertTrue(Files.isSymbolicLink(target));
        assertEquals(source, Files.readSymbolicLink(target));
        assertTrue(Files.isRegularFile(root.resolve("link.bak").resolve("old.txt")));
    }

    @Test
    void failsWhenTargetParentDoesNotExist() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("missing-dir").resolve("link.txt");

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));
        assertTrue(failure.getMessage().contains("父目录"), failure.getMessage());
    }
}
