package io.xac.systemd.apply;

/** 一个待执行动作的事实性描述；面向人的 "would" 前缀由 CLI 决定。 */
public final class StepPlan {

    public final String description;
    public final boolean destructive;

    public StepPlan(String description, boolean destructive) {
        this.description = description;
        this.destructive = destructive;
    }
}
