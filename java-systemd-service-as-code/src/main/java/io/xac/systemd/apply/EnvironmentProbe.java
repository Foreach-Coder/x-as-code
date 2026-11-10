package io.xac.systemd.apply;

import java.nio.file.Path;

public interface EnvironmentProbe {

    boolean isRoot();

    boolean systemdRunning();

    boolean fileReadable(Path path);

    boolean executable(Path path);
}
