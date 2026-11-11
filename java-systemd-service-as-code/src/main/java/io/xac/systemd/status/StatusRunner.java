package io.xac.systemd.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemctlShow;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;

public final class StatusRunner {

    /** 读取文件内容；测试注入以模拟权限不足（env 文件为 0600 root 所有）。 */
    interface ContentReader {
        String read(Path path) throws IOException;
    }

    private static final ContentReader FILES = new ContentReader() {
        @Override
        public String read(Path path) throws IOException {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }
    };

    private enum EnvState {
        MATCH, DIFFER, MISSING, UNREADABLE
    }

    private final ContentReader envReader;

    public StatusRunner() {
        this(FILES);
    }

    StatusRunner(ContentReader envReader) {
        this.envReader = envReader;
    }

    public StatusResult run(ServiceSpec spec, SystemdTarget target, CommandRunner runner) {
        UnitRenderer renderer = new UnitRenderer();
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);

        boolean unitInSync = contentEquals(unitPath, renderer.render(spec, target));
        boolean envConfigured = !spec.env.isEmpty();
        boolean envInstalled = Files.isRegularFile(envPath);
        boolean envInSync;
        boolean envReadable = true;
        if (!envConfigured) {
            envInSync = !Files.exists(envPath);
        } else {
            EnvState envState = readEnv(envPath, renderer.renderEnvFile(spec));
            envInSync = envState == EnvState.MATCH;
            envReadable = envState != EnvState.UNREADABLE;
        }

        List<String> linkProblems = checkLinks(spec);
        SystemctlShow.Query show = SystemctlShow.query(runner, UnitNaming.unitName(spec));

        boolean wantEnabled = spec.lifecycle.autoStartOrDefault();
        boolean enabled = "enabled".equals(show.values.get("UnitFileState"));
        return new StatusResult(spec.name, spec.packageDir, unitPath, Files.isRegularFile(unitPath),
                unitInSync, envPath, envConfigured, envInstalled, envReadable, envInSync,
                show.values, show.ok, enabled, wantEnabled, linkProblems);
    }

    private EnvState readEnv(Path path, String expected) {
        if (!Files.isRegularFile(path)) {
            return EnvState.MISSING;
        }
        try {
            return expected.equals(envReader.read(path)) ? EnvState.MATCH : EnvState.DIFFER;
        } catch (IOException e) {
            return EnvState.UNREADABLE;
        }
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
                    problems.add("path-links[" + i + "] not active: " + link.target);
                }
            } catch (IOException e) {
                problems.add("path-links[" + i + "] cannot check: " + link.target + " (" + e.getMessage() + ")");
            }
        }
        return problems;
    }
}
