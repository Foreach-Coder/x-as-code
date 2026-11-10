package io.xac.systemd.apply;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreflightTest {

    @TempDir
    Path pkg;

    private ServiceSpec spec(String jarName) {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .jar(jarName)
                .java("/usr/bin/java")
                .opts(java.util.Collections.<String>emptyList())
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private static final class FakeProbe implements EnvironmentProbe {
        boolean root = true;
        boolean systemd = true;
        boolean javaExecutable = true;

        @Override
        public boolean isRoot() { return root; }

        @Override
        public boolean systemdRunning() { return systemd; }

        @Override
        public boolean fileReadable(Path path) { return Files.isRegularFile(path); }

        @Override
        public boolean executable(Path path) { return javaExecutable; }
    }

    @Test
    void passesWhenEverythingIsFine() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        assertTrue(Preflight.check(spec("app.jar"), new FakeProbe()).isEmpty());
    }

    @Test
    void requiresJarToExist() {
        List<Violation> out = Preflight.check(spec("app.jar"), new FakeProbe());
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("artifact.jar"), out.get(0).toString());
    }

    @Test
    void requiresRootAndSystemd() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.root = false;
        probe.systemd = false;
        List<Violation> out = Preflight.check(spec("app.jar"), probe);
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).toString().contains("root"));
        assertTrue(out.get(1).toString().contains("systemd"));
    }

    @Test
    void requiresJavaToBeExecutable() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.javaExecutable = false;
        List<Violation> out = Preflight.check(spec("app.jar"), probe);
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("runtime.java"), out.get(0).toString());
    }

    @Test
    void destroyCheckSkipsArtifactAndJava() {
        FakeProbe probe = new FakeProbe();
        probe.javaExecutable = false;

        assertTrue(Preflight.checkDestroy(probe).isEmpty());
    }
}
