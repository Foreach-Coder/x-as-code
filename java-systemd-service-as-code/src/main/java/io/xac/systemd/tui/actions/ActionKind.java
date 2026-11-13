package io.xac.systemd.tui.actions;

/**
 * 动作面板对应的三类管理动作（design §6.6）。面板只负责“预览 + 确认”，
 * 真正执行仍在 {@code core.ServicePackage} 上，TUI 不重新实现任何写盘语义。
 */
public enum ActionKind {

    APPLY,
    RESTART,
    DESTROY;

    /** 用于面板标题和 notice 前缀的稳定小写名，运行时文案必须是英文 ASCII。 */
    public String label() {
        switch (this) {
            case APPLY:
                return "apply";
            case RESTART:
                return "restart";
            case DESTROY:
                return "destroy";
            default:
                throw new IllegalStateException("unhandled action kind: " + this);
        }
    }
}
