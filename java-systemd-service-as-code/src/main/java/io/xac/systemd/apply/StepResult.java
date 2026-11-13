package io.xac.systemd.apply;

/**
 * 一步的结果。
 *
 * <ul>
 *   <li>{@code ok=true, message=null}：计划（dry-run）或执行完成且无需补充说明；</li>
 *   <li>{@code ok=true, message!=null}：执行完成但有警告，message 是事实性说明；</li>
 *   <li>{@code ok=false, message!=null}：失败原因。</li>
 * </ul>
 *
 * <p>{@code plan == null} 表示这是一条 <strong>warning-only 记录</strong>：它只承载
 * {@code message}，不对应任何动作（destroy 用它把「循环开始前」的警告保留在输出顺序里）。
 * 消费方必须先判空再读 {@link #plan}，不能假定每一步都有动作描述。
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
