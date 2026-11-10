package io.xac.systemd.apply;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;

public final class Preflight {

    /** 依赖当前机器的检查；通过后才允许改动目标机。 */
    public static List<Violation> check(ServiceSpec spec, EnvironmentProbe probe) {
        List<Violation> out = new ArrayList<Violation>();
        if (!probe.isRoot()) {
            out.add(new Violation("", "apply / destroy 需要 root 权限"));
        }
        if (!probe.systemdRunning()) {
            out.add(new Violation("", "systemd 未运行（/run/systemd/system 不存在）"));
        }
        if (!probe.fileReadable(spec.jar)) {
            out.add(new Violation("artifact.jar", "文件不存在: " + spec.jar));
        }
        if (!probe.executable(Paths.get(spec.java))) {
            out.add(new Violation("runtime.java", "不存在或不可执行: " + spec.java));
        }
        return out;
    }

    private Preflight() {
    }
}
