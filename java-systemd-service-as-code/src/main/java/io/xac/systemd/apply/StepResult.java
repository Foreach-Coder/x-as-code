package io.xac.systemd.apply;

/**
 * 一步的结果。
 *
 * <ul>
 *   <li>{@code ok=true, message=null}：计划（dry-run）或执行完成且无需补充说明；</li>
 *   <li>{@code ok=true, message!=null}：执行完成但有警告，message 是事实性说明；</li>
 *   <li>{@code ok=false, message!=null}：失败原因。</li>
 * </ul>
 */
public final class StepResult {

    public final StepPlan plan;
    public final boolean ok;
    public final String message;

    public StepResult(StepPlan plan, boolean ok, String message) {
        this.plan = plan;
        this.ok = ok;
        this.message = message;
    }
}
