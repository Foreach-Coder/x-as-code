package io.xac.systemd.exec;

public final class ExecResult {

    public final int exitCode;
    public final String stdout;
    public final String stderr;

    public ExecResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public boolean ok() {
        return exitCode == 0;
    }
}
