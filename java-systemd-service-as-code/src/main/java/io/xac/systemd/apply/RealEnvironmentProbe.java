package io.xac.systemd.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;

public final class RealEnvironmentProbe implements EnvironmentProbe {

    private static final Path SYSTEMD_RUNTIME = Paths.get("/run/systemd/system");

    private final CommandRunner runner;

    public RealEnvironmentProbe(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean isRoot() {
        ExecResult result = runner.run(Arrays.asList("id", "-u"));
        return result.ok() && "0".equals(result.stdout.trim());
    }

    @Override
    public boolean systemdRunning() {
        return Files.isDirectory(SYSTEMD_RUNTIME);
    }

    @Override
    public boolean fileReadable(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public boolean executable(Path path) {
        return Files.isExecutable(path);
    }
}
