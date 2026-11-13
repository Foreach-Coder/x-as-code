package io.xac.systemd.tui;

/**
 * 单元格的语义角色。Canvas 只记录角色，不依赖任何颜色库；
 * {@link LanternaDisplay} 负责把角色映射成终端颜色，测试替身也可以直接断言角色。
 */
public enum Role {

    /** 终端默认前景/背景。 */
    DEFAULT,

    /** 边框、标题、面板边框等结构性元素。 */
    ACCENT,

    /** 次要信息：表头、提示、键位条。 */
    DIM,

    /** 漂移、错误、失败结果。 */
    WARN,

    /** 成功结果。 */
    OK,

    /** 列表/字段的当前选中行。 */
    SELECTED
}
