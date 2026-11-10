package io.xac.systemd.systemd;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class SystemdTarget {

    public final Path unitDir;
    public final Path envDir;

    public SystemdTarget(Path unitDir, Path envDir) {
        this.unitDir = unitDir;
        this.envDir = envDir;
    }

    public static SystemdTarget defaults() {
        return new SystemdTarget(Paths.get("/etc/systemd/system"), Paths.get("/etc/xac"));
    }
}
