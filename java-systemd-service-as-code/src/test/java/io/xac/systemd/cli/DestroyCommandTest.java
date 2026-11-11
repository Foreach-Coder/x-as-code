package io.xac.systemd.cli;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyCommandTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(String... links) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .pathLinks(new ArrayList<String>(Arrays.asList(links)))
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private static String printPlan(ServiceSpec spec, SystemdTarget target) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DestroyCommand.printPlan(spec, target, new PrintStream(buffer, true, "UTF-8"));
        return buffer.toString("UTF-8");
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
    void printsFilesToDeleteAndWhatSurvives() throws IOException {
        ServiceSpec spec = spec();
        SystemdTarget target = target();
        install(spec, target);

        String out = printPlan(spec, target);

        assertTrue(out.contains("will remove:"), out);
        assertTrue(out.contains(UnitNaming.unitPath(spec, target).toString()), out);
        assertTrue(out.contains(UnitNaming.envPath(spec, target).toString()), out);
        assertTrue(out.contains("keep: package directory " + root.toAbsolutePath().normalize()), out);
        assertTrue(out.contains(", logs"), out);
    }

    @Test
    void printsNoneWhenNothingIsInstalled() throws IOException {
        String out = printPlan(spec(), target());

        assertTrue(out.contains("will remove:"), out);
        assertTrue(out.contains("  (none)"), out);
    }

    @Test
    void leavesForeignUnitOutOfTheDeleteList() throws IOException {
        ServiceSpec spec = spec();
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.write(UnitNaming.unitPath(spec, target),
                "Description=foreign\n".getBytes(StandardCharsets.UTF_8));

        String out = printPlan(spec, target);

        assertTrue(out.contains("  (none)"), out);
        assertFalse(out.contains(UnitNaming.unitPath(spec, target).toString()), out);
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
    void printsWorkdirWhenConfigured() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .workdir("run")
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();

        String out = printPlan(spec, target());

        assertTrue(out.contains("workdir " + root.resolve("run").toAbsolutePath().normalize()), out);
    }

    @Test
    void listsOnlyLinksThatPointToTheirSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);
        Path foreign = root.resolve("foreign");
        Files.createSymbolicLink(foreign, Files.createDirectories(root.resolve("other")));

        String out = printPlan(spec(source + ":" + linked, source + ":" + foreign), target());

        assertTrue(out.contains(linked.toAbsolutePath().normalize().toString()), out);
        assertFalse(out.contains(foreign.toAbsolutePath().normalize().toString()), out);
    }
}
