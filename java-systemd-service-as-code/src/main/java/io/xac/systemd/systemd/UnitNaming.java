package io.xac.systemd.systemd;

import java.nio.file.Path;

import io.xac.systemd.spec.ServiceSpec;

public final class UnitNaming {

    public static String unitName(ServiceSpec spec) {
        return spec.name + ".service";
    }

    public static String envFileName(ServiceSpec spec) {
        return spec.name + ".env";
    }

    public static Path unitPath(ServiceSpec spec, SystemdTarget target) {
        return target.unitDir.resolve(unitName(spec));
    }

    public static Path envPath(ServiceSpec spec, SystemdTarget target) {
        return target.envDir.resolve(envFileName(spec));
    }

    private UnitNaming() {
    }
}
