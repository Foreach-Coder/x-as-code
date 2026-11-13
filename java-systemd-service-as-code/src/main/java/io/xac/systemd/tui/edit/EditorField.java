package io.xac.systemd.tui.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.spec.Schema;

/**
 * 编辑表单中的一个字段：规范路径、控件类型、当前文本值与注释帮助。
 *
 * <p>所有编辑都发生在文本层：{@link #values} 对 STRING/BOOLEAN/INTEGER 恒为 1 个元素，
 * 对 STRING_LIST/STRING_MAP 为 N 行（{@code k=v} 形式）。语义转换与校验在
 * {@link EditorModel#toSpec()} 与 {@link EditorModel#validate()} 中完成。</p>
 */
public final class EditorField {

    /** 与 {@link Schema} 一致的规范路径，例如 {@code runtime.opts}。 */
    public final String path;

    /** 控件类型，由路径决定，不可编辑。 */
    public final Schema.Type type;

    /** {@code name} 只读：改名等于换服务，应新建包目录。 */
    public final boolean readOnly;

    /** 表单中的文本值；不可变。 */
    public final List<String> values;

    /** 枚举取值；首项 {@code ""} 表示缺省。非枚举字段为空列表。 */
    public final List<String> options;

    /** 英文行标签。 */
    public final String label;

    /** 来自既有注释的帮助文本；没有注释时为 {@code ""}。 */
    public final String help;

    public EditorField(String path,
                       Schema.Type type,
                       boolean readOnly,
                       List<String> values,
                       List<String> options,
                       String label,
                       String help) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null for " + path);
        }
        if (values == null) {
            throw new IllegalArgumentException("values must not be null for " + path);
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null for " + path);
        }
        this.path = path;
        this.type = type;
        this.readOnly = readOnly;
        this.values = Collections.unmodifiableList(new ArrayList<String>(values));
        this.options = Collections.unmodifiableList(new ArrayList<String>(options));
        this.label = label == null ? path : label;
        this.help = help == null ? "" : help;
    }

    EditorField withValues(List<String> newValues) {
        return new EditorField(path, type, readOnly, newValues, options, label, help);
    }

    @Override
    public String toString() {
        return path + "=" + values;
    }
}
