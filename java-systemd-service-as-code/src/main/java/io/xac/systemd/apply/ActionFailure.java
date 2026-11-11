package io.xac.systemd.apply;

public class ActionFailure extends RuntimeException {

    public ActionFailure(String message) {
        super(message);
    }

    public ActionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
