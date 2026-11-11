package io.xac.systemd.spec;

public final class Violation {

    public final String path;
    public final String message;

    public Violation(String path, String message) {
        this.path = path;
        this.message = message;
    }

    @Override
    public String toString() {
        return path.isEmpty() ? message : path + ": " + message;
    }
}
