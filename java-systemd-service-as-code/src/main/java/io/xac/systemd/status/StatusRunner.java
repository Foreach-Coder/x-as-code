package io.xac.systemd.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemctlShow;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;

public final class StatusRunner {

    public StatusResult run(ServiceSpec spec, SystemdTarget target, CommandRunner runner) {
        UnitRenderer renderer = new UnitRenderer();
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);

        boolean unitInSync = contentEquals(unitPath, renderer.render(spec, target));
        boolean envConfigured = !spec.env.isEmpty();
        boolean envInstalled = Files.isRegularFile(envPath);
        boolean envInSync = envConfigured
                ? contentEquals(envPath, renderer.renderEnvFile(spec))
                : !Files.exists(envPath);

        List<String> linkProblems = checkLinks(spec);
        Map<String, String> show = SystemctlShow.show(runner, UnitNaming.unitName(spec));

        boolean wantEnabled = spec.lifecycle.autoStartOrDefault();
        boolean enabled = "enabled".equals(show.get("UnitFileState"));
        return new StatusResult(spec.name, spec.packageDir, unitPath, Files.isRegularFile(unitPath),
                unitInSync, envPath, envConfigured, envInstalled, envInSync, show, enabled,
                wantEnabled, linkProblems);
    }

    private static boolean contentEquals(Path path, String expected) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            return expected.equals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> checkLinks(ServiceSpec spec) {
        List<String> problems = new ArrayList<String>();
        for (int i = 0; i < spec.pathLinks.size(); i++) {
            PathLink link = PathLink.parse(spec.pathLinks.get(i), spec.packageDir);
            if (link == null) {
                continue;
            }
            try {
                boolean ok = Files.isSymbolicLink(link.target)
                        && Files.readSymbolicLink(link.target).equals(link.source);
                if (!ok) {
                    problems.add("path-links[" + i + "] 未生效: " + link.target);
                }
            } catch (IOException e) {
                problems.add("path-links[" + i + "] 无法检查: " + link.target + " (" + e.getMessage() + ")");
            }
        }
        return problems;
    }
}
