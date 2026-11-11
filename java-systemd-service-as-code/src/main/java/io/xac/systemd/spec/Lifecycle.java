package io.xac.systemd.spec;

public final class Lifecycle {

    public final Boolean autoStart;
    public final String restart;
    public final Integer restartSec;
    public final Integer timeoutStopSec;

    public Lifecycle(Boolean autoStart, String restart, Integer restartSec, Integer timeoutStopSec) {
        this.autoStart = autoStart;
        this.restart = restart;
        this.restartSec = restartSec;
        this.timeoutStopSec = timeoutStopSec;
    }

    public boolean autoStartOrDefault() {
        return autoStart == null || autoStart;
    }

    public String restartOrDefault() {
        return restart == null ? "on-failure" : restart;
    }

    public int restartSecOrDefault() {
        return restartSec == null ? 5 : restartSec;
    }

    public int timeoutStopSecOrDefault() {
        return timeoutStopSec == null ? 60 : timeoutStopSec;
    }
}
