package io.xac.systemd.apply;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.apply.actions.ManagedUnit;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.PathLinkGuard;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemctlShow;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;

public final class Preflight {

    /** 依赖当前机器的检查；通过后才允许改动目标机。 */
    public static List<Violation> check(ServiceSpec spec, EnvironmentProbe probe,
            CommandRunner runner, SystemdTarget target) {
        List<Violation> out = new ArrayList<Violation>();
        checkEnvironment(probe, out);
        if (!probe.fileReadable(spec.jar)) {
            out.add(new Violation("artifact.jar", "file does not exist: " + spec.jar));
        }
        if (!probe.executable(Paths.get(spec.java))) {
            out.add(new Violation("runtime.java", "does not exist or is not executable: " + spec.java));
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
        checkNoShadowingUnit(spec, runner, target, out);
        checkUnitFileOwnership(spec, target, out);
        checkEnvFileOwnership(spec, target, out);
        return out;
    }

    /**
     * 同名 unit 只要由 systemd 提供（FragmentPath 指向别处），apply 就会覆盖它，
     * 进而在 enable/restart 时劫持甚至停掉系统服务。查询失败同样不能放行。
     */
    private static void checkNoShadowingUnit(ServiceSpec spec, CommandRunner runner,
            SystemdTarget target, List<Violation> out) {
        SystemctlShow.Query query = SystemctlShow.query(runner, UnitNaming.unitName(spec));
        if (!query.ok) {
            out.add(new Violation("unit",
                    "cannot query unit with same name (systemctl show failed), handle it manually"));
            return;
        }
        String fragmentPath = query.values.get("FragmentPath");
        if (fragmentPath == null || fragmentPath.isEmpty()) {
            return;
        }
        Path actual = Paths.get(fragmentPath).toAbsolutePath().normalize();
        Path expected = UnitNaming.unitPath(spec, target).toAbsolutePath().normalize();
        if (!expected.equals(actual)) {
            out.add(new Violation("unit",
                    "unit with same name exists and is not managed by xac: " + fragmentPath + ", handle it manually"));
        }
    }

    private static void checkUnitFileOwnership(ServiceSpec spec, SystemdTarget target,
            List<Violation> out) {
        Path unitPath = UnitNaming.unitPath(spec, target);
        if (ManagedUnit.existsAndIsForeign(unitPath)) {
            out.add(new Violation("unit", "existing unit file is not managed by xac, refusing to overwrite: " + unitPath));
        }
    }

    private static void checkEnvFileOwnership(ServiceSpec spec, SystemdTarget target,
            List<Violation> out) {
        Path envPath = UnitNaming.envPath(spec, target);
        if (ManagedUnit.existsAndIsForeign(envPath)) {
            out.add(new Violation("env", "existing env file is not managed by xac, refusing to overwrite or remove: " + envPath));
        }
    }

    /** teardown 不能依赖产物还存在：只检查卸载所需的 root 与 systemd。 */
    public static List<Violation> checkDestroy(EnvironmentProbe probe) {
        List<Violation> out = new ArrayList<Violation>();
        checkEnvironment(probe, out);
        return out;
    }

    private static void checkEnvironment(EnvironmentProbe probe, List<Violation> out) {
        if (!probe.isRoot()) {
            out.add(new Violation("", "apply / destroy require root"));
        }
        if (!probe.systemdRunning()) {
            out.add(new Violation("", "systemd is not running (/run/systemd/system missing)"));
        }
    }

    private Preflight() {
    }
}
