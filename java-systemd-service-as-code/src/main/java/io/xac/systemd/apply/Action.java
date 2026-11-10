package io.xac.systemd.apply;

public interface Action {

    String describe(ApplyContext ctx);

    void run(ApplyContext ctx);
}
