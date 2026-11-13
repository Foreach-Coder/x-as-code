package io.xac.systemd.tui.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.Schema;

/**
 * 编辑表单的纯文本渲染：每个字段一行，下面是两行按键提示。
 *
 * <p>渲染只依赖 {@link EditorModel} 和宽度，不碰终端、不持有状态，因此可以在测试里直接断言字符串。
 * 光标所在行用 {@code "> "} 标记，其余行用两个空格对齐；值从第 30 列开始，超宽时截断。
 * 行尾可能再挂一段 {@code "# "} 注释（来自原 yaml 的注释）或 {@code "! "} 校验错误——
 * 错误优先，因为它是用户下一步必须处理的东西。</p>
 */
public final class EditorView {

    /** 值列的起始列（含前缀标记）。 */
    static final int VALUE_COLUMN = 30;

    public static final String HINT_EDIT = "enter edit  space toggle  s save";
    public static final String HINT_KEYS = "u undo  q cancel";

    private static final String FOCUS = "> ";
    private static final String PLAIN = "  ";
    private static final String READ_ONLY = " (read-only)";
    private static final String HELP = "  # ";
    private static final String ERROR = "  ! ";
    private static final String MORE = ", ... (";

    private EditorView() {
    }

    /** 渲染 14 个字段 + 两行提示。{@code errors} 以字段 path 为键。 */
    public static List<String> lines(EditorModel model, int focus, int width,
                                     Map<String, String> errors) {
        return lines(model, focus, width, errors, null, null);
    }

    /**
     * 渲染表单；{@code editingPath} 非空时该行显示为内联输入状态（值来自 {@code buffer}）。
     */
    public static List<String> lines(EditorModel model, int focus, int width,
                                     Map<String, String> errors, String editingPath,
                                     String buffer) {
        return lines(model, focus, width, errors, editingPath, buffer,
                buffer == null ? 0 : buffer.length());
    }

    /**
     * 渲染表单；{@code cursor} 是 {@code buffer} 里的插入点（UTF-16 偏移），
     * 画成 {@code _} 光标。
     */
    public static List<String> lines(EditorModel model, int focus, int width,
                                     Map<String, String> errors, String editingPath,
                                     String buffer, int cursor) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        Map<String, String> errorMap = errors == null
                ? Collections.<String, String>emptyMap() : errors;
        int w = Math.max(1, width);
        List<EditorField> fields = model.fields();
        List<String> out = new ArrayList<String>(fields.size() + 2);
        for (int i = 0; i < fields.size(); i++) {
            EditorField field = fields.get(i);
            String error = errorMap.get(field.path);
            boolean editing = editingPath != null && editingPath.equals(field.path);
            out.add(row(field, i == focus, editing, buffer, cursor, error, w));
        }
        if (editingPath != null) {
            out.add(clip("type to edit  enter apply  esc cancel", w));
            out.add(clip("backspace delete", w));
        } else {
            out.add(clip(HINT_EDIT, w));
            out.add(clip(HINT_KEYS, w));
        }
        return out;
    }

    private static String row(EditorField field, boolean focused, boolean editing,
                              String buffer, int cursor, String error, int width) {
        StringBuilder line = new StringBuilder();
        line.append(focused ? FOCUS : PLAIN).append(field.path);
        while (line.length() < VALUE_COLUMN) {
            line.append(' ');
        }
        if (line.length() > VALUE_COLUMN) {
            line.append(' ');
        }
        if (editing) {
            line.append("> ").append(withCursor(buffer, cursor));
        } else {
            line.append(valueText(field));
            if (field.readOnly) {
                line.append(READ_ONLY);
            }
        }
        if (error != null && !error.isEmpty()) {
            line.append(ERROR).append(oneLine(error));
        } else if (field.help != null && !field.help.isEmpty() && !editing) {
            line.append(HELP).append(oneLine(field.help));
        }
        return clip(line.toString(), width);
    }

    /** 在插入点画 {@code _}；越界按末尾处理，不抛异常。 */
    private static String withCursor(String buffer, int cursor) {
        String text = buffer == null ? "" : buffer;
        int at = Math.max(0, Math.min(cursor, text.length()));
        return text.substring(0, at) + '_' + text.substring(at);
    }

    private static String valueText(EditorField field) {
        List<String> values = field.values;
        if (values == null || values.isEmpty()) {
            switch (field.type) {
                case STRING_LIST:
                case STRING_MAP:
                    return "0 rows";
                default:
                    return "";
            }
        }
        if (field.type == Schema.Type.STRING_LIST
                || field.type == Schema.Type.STRING_MAP) {
            int shown = Math.min(values.size(), 4);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(values.get(i));
            }
            if (values.size() > shown) {
                text.append(MORE).append(values.size()).append(" rows)");
            }
            return text.toString();
        }
        return values.get(0);
    }

    private static String oneLine(String text) {
        return text.replace("\r", " ").replace("\n", " / ");
    }

    /** 按终端显示宽度截断（宽字符占两列），保证不会画到框外。 */
    static String clip(String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) {
            return "";
        }
        int column = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int cell = displayWidth(codePoint);
            if (column + cell > width) {
                break;
            }
            column += cell;
            index += Character.charCount(codePoint);
        }
        return index >= text.length() ? text : text.substring(0, index);
    }

    static int displayWidth(int codePoint) {
        if (codePoint < 0x1100) {
            return 1;
        }
        if (codePoint <= 0x115f
                || codePoint == 0x2329
                || codePoint == 0x232a
                || (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint != 0x303f)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe10 && codePoint <= 0xfe19)
                || (codePoint >= 0xfe30 && codePoint <= 0xfe6f)
                || (codePoint >= 0xff00 && codePoint <= 0xff60)
                || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
                || (codePoint >= 0x1f300 && codePoint <= 0x1f64f)
                || (codePoint >= 0x1f900 && codePoint <= 0x1f9ff)
                || (codePoint >= 0x20000 && codePoint <= 0x3fffd)) {
            return 2;
        }
        return 1;
    }
}
