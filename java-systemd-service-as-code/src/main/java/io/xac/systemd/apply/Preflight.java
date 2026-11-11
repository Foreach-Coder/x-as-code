package io.xac.systemd.apply;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.PathLinkGuard;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;

public final class Preflight {

    /** 依赖当前机器的检查；通过后才允许改动目标机。 */
    public static List<Violation> check(ServiceSpec spec, EnvironmentProbe probe) {
        List<Violation> out = new ArrayList<Violation>();
        checkEnvironment(probe, out);
        if (!probe.fileReadable(spec.jar)) {
            out.add(new Violation("artifact.jar", "文件不存在: " + spec.jar));
        }
        if (!probe.executable(Paths.get(spec.java))) {
            out.add(new Violation("runtime.java", "不存在或不可执行: " + spec.java));
        }
        for (int i = 0; i < spec.pathLinks.size(); i++) {
            PathLink link = PathLink.parse(spec.pathLinks.get(i), spec.packageDir);
            if (link == null) {
                continue;
            }
            String problem = PathLinkGuard.checkSource(link);
            if (problem == null) {
                problem = PathLinkGuard.checkTarget(link);
            }
            if (problem != null) {
                out.add(new Violation("path-links[" + i + "]", problem));
            }
        }
        return out;
    }

    /** teardown 不能依赖产物还存在：只检查卸载所需的 root 与 systemd。 */
    public static List<Violation> checkDestroy(EnvironmentProbe probe) {
        List<Violation> out = new ArrayList<Violation>();
        checkEnvironment(probe, out);
        return out;
    }

    private static void checkEnvironment(EnvironmentProbe probe, List<Violation> out) {
        if (!probe.isRoot()) {
            out.add(new Violation("", "apply / destroy 需要 root 权限"));
        }
        if (!probe.systemdRunning()) {
            out.add(new Violation("", "systemd 未运行（/run/systemd/system 不存在）"));
        }
    }

    private Preflight() {
    }
}
