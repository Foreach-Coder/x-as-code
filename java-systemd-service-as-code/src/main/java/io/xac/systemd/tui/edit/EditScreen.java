package io.xac.systemd.tui.edit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.LineDiff;
import io.xac.systemd.spec.Schema;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.tui.NavKey;

/**
 * 编辑屏：一个字段表单 + 三种模式（浏览、编辑单值、编辑行列表）。
 *
 * <p>屏幕是纯状态机，不认识终端：{@link #handle} 吃 {@link NavKey} 和字符，
 * {@link #lines} 产出要画的文本。真正的 yaml 读写交给 {@link SaveFlow}。
 * 未保存的改动只存在内存里，{@code u} 按整份 {@link EditorModel} 回滚，{@code q}
 * 在脏状态下先要一次 {@code y/n} 确认。</p>
 */
public final class EditScreen {

    public enum Mode { BROWSE, EDIT_SCALAR, EDIT_LINES, CONFIRM_SAVE }

    /** Tab 跳转的九个节段起点：name, artifact, runtime, env, properties, workdir, path-links, logs, lifecycle。 */
    private static final int[] SEGMENTS = {0, 2, 3, 5, 6, 7, 8, 9, 10};

    private static final int MAX_ERROR_LINES = 3;
    private static final int MAX_ROW_WINDOW = 10;
    private static final int KEY_WIDTH = 12;
    private static final int VALUE_WIDTH = 28;

    private final Path yamlFile;
    private FileStamp baseline;
    private final List<EditorModel> undoStack = new ArrayList<EditorModel>();

    private EditorModel model;
    private int focus;
    private Mode mode = Mode.BROWSE;
    private String buffer = "";
    /** 插入点在 {@link #buffer} 里的字符偏移（UTF-16），编辑态才有意义。 */
    private int cursor;
    private int lineIndex;
    private boolean rowEdit;
    /** 行编辑时正在改 key（映射才有）而不是 value。 */
    private boolean keyEdit;
    private String rowKey = "";
    private String rowValue = "";
    private boolean dirty;
    private boolean closed;
    private boolean saved;
    private boolean confirmDiscard;
    private List<Violation> saveError = Collections.emptyList();
    private List<String> saveNote = Collections.emptyList();
    /** 非空时处于保存预览：这份结果已经算好，等 {@code y} 落盘、{@code n/esc} 取消。 */
    private SaveFlow.Result pending;
    private int previewScroll;
    private boolean pendingExternal;
    /**
     * 最近一次全量校验的结果，用来画行尾 {@code !} 并定位光标；与 {@link #saveError}
     * 分开，避免二次确认提示被一次普通移动覆盖。
     */
    private List<Violation> validation = Collections.emptyList();

    public EditScreen(EditorModel model, Path yamlFile) throws IOException {
        this(model, yamlFile, FileStamp.of(yamlFile));
    }

    public EditScreen(EditorModel model, Path yamlFile, FileStamp baseline) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        if (model.fields().isEmpty()) {
            throw new IllegalArgumentException("model has no fields");
        }
        this.model = model;
        this.yamlFile = yamlFile;
        this.baseline = baseline;
        this.focus = firstEditable();
        refreshValidation();
        if (!validation.isEmpty()) {
            focusFirst(validation);
        }
    }

    // ------------------------------------------------------------- accessors

    public EditorModel model() {
        return model;
    }

    public boolean dirty() {
        return dirty;
    }

    public boolean closed() {
        return closed;
    }

    /** 本次退出是否因为保存成功；TuiApp 据此决定要不要重新打开包。 */
    public boolean saved() {
        return saved;
    }

    public int focus() {
        return focus;
    }

    public Mode mode() {
        return mode;
    }

    public List<String> saveNote() {
        return saveNote;
    }

    // ---------------------------------------------------------------- input

    public void handle(NavKey key, char ch) {
        if (closed) {
            return;
        }
        if (confirmDiscard) {
            handleConfirm(key, ch);
            return;
        }
        if (mode == Mode.CONFIRM_SAVE) {
            handlePreview(key, ch);
            return;
        }
        if (mode == Mode.EDIT_SCALAR) {
            handleScalar(key, ch);
            return;
        }
        if (mode == Mode.EDIT_LINES) {
            handleLines(key, ch);
            return;
        }
        handleBrowse(key, ch);
    }

    private void handleConfirm(NavKey key, char ch) {
        if (key == NavKey.ESC || key == NavKey.QUIT
                || (key == NavKey.CHAR && (ch == 'n' || ch == 'N'))) {
            confirmDiscard = false;
            return;
        }
        if (key == NavKey.CHAR && (ch == 'y' || ch == 'Y')) {
            closed = true;
            saved = false;
        }
    }

    private void handleScalar(NavKey key, char ch) {
        switch (key) {
            case ESC:
                mode = rowEdit ? Mode.EDIT_LINES : Mode.BROWSE;
                rowEdit = false;
                keyEdit = false;
                clearBuffer();
                return;
            case ENTER:
                applyBuffer();
                return;
            case LEFT:
                if (cursor > 0) {
                    cursor = buffer.offsetByCodePoints(cursor, -1);
                }
                return;
            case RIGHT:
                if (cursor < buffer.length()) {
                    cursor = buffer.offsetByCodePoints(cursor, 1);
                }
                return;
            case TOP:
                cursor = 0;
                return;
            case BOTTOM:
                cursor = buffer.length();
                return;
            case BACKSPACE:
                if (cursor > 0) {
                    int from = buffer.offsetByCodePoints(cursor, -1);
                    buffer = buffer.substring(0, from) + buffer.substring(cursor);
                    cursor = from;
                }
                return;
            case DELETE:
                if (cursor < buffer.length()) {
                    int to = buffer.offsetByCodePoints(cursor, 1);
                    buffer = buffer.substring(0, cursor) + buffer.substring(to);
                }
                return;
            case CHAR:
                if (ch >= 0x20 && ch != 0x7f) {
                    buffer = buffer.substring(0, cursor) + ch + buffer.substring(cursor);
                    cursor++;
                }
                return;
            default:
                return;
        }
    }

    /** 把缓冲区置成 {@code text}，插入点放在末尾。 */
    private void setBuffer(String text) {
        buffer = text == null ? "" : text;
        cursor = buffer.length();
    }

    private void clearBuffer() {
        buffer = "";
        cursor = 0;
    }

    private void applyBuffer() {
        EditorField field = current();
        if (rowEdit) {
            List<String> values = new ArrayList<String>(field.values);
            boolean changed;
            if (lineIndex >= 0 && lineIndex < values.size()) {
                String next;
                if (field.type == Schema.Type.STRING_MAP) {
                    String key = keyEdit ? buffer : rowKey;
                    String value = keyEdit ? rowValue : buffer;
                    next = key + "=" + value;
                } else {
                    next = buffer;
                }
                changed = !next.equals(values.get(lineIndex));
                values.set(lineIndex, next);
            } else {
                values.add(buffer);
                lineIndex = values.size() - 1;
                changed = true;
            }
            if (changed) {
                mutate(field.path, values);
            }
            rowEdit = false;
            keyEdit = false;
            clearBuffer();
            mode = Mode.EDIT_LINES;
            return;
        }
        String existing = field.values.isEmpty() ? "" : field.values.get(0);
        if (!buffer.equals(existing)) {
            mutate(field.path, Collections.singletonList(buffer));
        }
        clearBuffer();
        mode = Mode.BROWSE;
    }

    private void handleLines(NavKey key, char ch) {
        EditorField field = current();
        switch (key) {
            case ESC:
            case LEFT:
                mode = Mode.BROWSE;
                return;
            case UP:
                if (lineIndex > 0) {
                    lineIndex--;
                }
                return;
            case DOWN:
                if (lineIndex + 1 < field.values.size()) {
                    lineIndex++;
                }
                return;
            case ENTER:
                startRowEdit(field, false);
                return;
            case CHAR:
                if (ch == 'a') {
                    addRow(field);
                } else if (ch == 'x') {
                    deleteRow(field);
                } else if (ch == 'e') {
                    startRowEdit(field, false);
                } else if (ch == 'k' && field.type == Schema.Type.STRING_MAP) {
                    startRowEdit(field, true);
                } else if (ch == 'J') {
                    moveRow(field, 1);
                } else if (ch == 'K') {
                    moveRow(field, -1);
                }
                return;
            default:
                return;
        }
    }

    /** 进入行编辑：映射可以选改 key 还是 value。 */
    private void startRowEdit(EditorField field, boolean editKey) {
        if (field.values.isEmpty()) {
            return;
        }
        lineIndex = Math.max(0, Math.min(lineIndex, field.values.size() - 1));
        String row = field.values.get(lineIndex);
        if (field.type == Schema.Type.STRING_MAP) {
            int eq = row.indexOf('=');
            rowKey = eq < 0 ? row : row.substring(0, eq);
            rowValue = eq < 0 ? "" : row.substring(eq + 1);
        } else {
            rowKey = "";
            rowValue = row;
        }
        keyEdit = editKey;
        setBuffer(editKey ? rowKey : rowValue);
        rowEdit = true;
        mode = Mode.EDIT_SCALAR;
    }

    /** 上移/下移一行：只交换数组元素，注释按值/key 索引，保存时仍跟对。 */
    private void moveRow(EditorField field, int delta) {
        int target = lineIndex + delta;
        if (lineIndex < 0 || target < 0 || target >= field.values.size()) {
            return;
        }
        List<String> values = new ArrayList<String>(field.values);
        String moved = values.get(lineIndex);
        values.set(lineIndex, values.get(target));
        values.set(target, moved);
        mutate(field.path, values);
        lineIndex = target;
    }

    private void addRow(EditorField field) {
        List<String> values = new ArrayList<String>(field.values);
        values.add("");
        mutate(field.path, values);
        lineIndex = values.size() - 1;
    }

    private void deleteRow(EditorField field) {
        if (field.values.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<String>(field.values);
        int index = Math.max(0, Math.min(lineIndex, values.size() - 1));
        values.remove(index);
        mutate(field.path, values);
        lineIndex = values.isEmpty() ? 0 : Math.min(index, values.size() - 1);
    }

    private void handleBrowse(NavKey key, char ch) {
        switch (key) {
            case TAB:
                focus = nextSegment(focus);
                return;
            case SHIFT_TAB:
                focus = previousSegment(focus);
                return;
            case UP:
                moveFocus(-1);
                return;
            case DOWN:
                moveFocus(1);
                return;
            case RIGHT:
                if (isMulti(current())) {
                    enterLines();
                }
                return;
            case ENTER:
                if (current().readOnly) {
                    return;
                }
                if (isMulti(current())) {
                    enterLines();
                } else {
                    enterScalar();
                }
                return;
            case ESC:
            case QUIT:
                requestClose();
                return;
            case CHAR:
                handleBrowseChar(ch);
                return;
            default:
                return;
        }
    }

    private void handleBrowseChar(char ch) {
        switch (ch) {
            case 's':
                save();
                return;
            case 'u':
                undo();
                return;
            case 'q':
                requestClose();
                return;
            case 'c':
                clearScalar();
                return;
            case ' ':
                toggleBoolean();
                return;
            case 'l':
                if (isMulti(current())) {
                    enterLines();
                }
                return;
            default:
                return;
        }
    }

    // ------------------------------------------------------------- actions

    private void moveFocus(int direction) {
        List<EditorField> fields = model.fields();
        int index = focus + direction;
        while (index >= 0 && index < fields.size() && fields.get(index).readOnly) {
            index += direction;
        }
        if (index >= 0 && index < fields.size()) {
            focus = index;
        }
    }

    private int nextSegment(int from) {
        for (int segment : SEGMENTS) {
            if (segment > from) {
                return segment;
            }
        }
        return SEGMENTS[0];
    }

    private int previousSegment(int from) {
        for (int i = SEGMENTS.length - 1; i >= 0; i--) {
            if (SEGMENTS[i] < from) {
                return SEGMENTS[i];
            }
        }
        return SEGMENTS[SEGMENTS.length - 1];
    }

    private void enterScalar() {
        setBuffer(current().values.isEmpty() ? "" : current().values.get(0));
        rowEdit = false;
        mode = Mode.EDIT_SCALAR;
    }

    private void enterLines() {
        lineIndex = 0;
        mode = Mode.EDIT_LINES;
    }

    private void clearScalar() {
        EditorField field = current();
        if (field.readOnly || isMulti(field)) {
            return;
        }
        mutate(field.path, Collections.singletonList(""));
    }

    private void toggleBoolean() {
        EditorField field = current();
        if (field.readOnly || field.type != Schema.Type.BOOLEAN) {
            return;
        }
        String raw = field.values.isEmpty() ? "" : field.values.get(0).trim();
        String next;
        if ("true".equals(raw)) {
            next = "false";
        } else if ("false".equals(raw)) {
            next = "";
        } else {
            next = "true";
        }
        mutate(field.path, Collections.singletonList(next));
    }

    private void mutate(String path, List<String> values) {
        undoStack.add(model);
        model = model.with(path, values);
        dirty = true;
        saveError = Collections.emptyList();
        saveNote = Collections.emptyList();
        refreshValidation();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            saveNote = Collections.singletonList("nothing to undo");
            return;
        }
        model = undoStack.remove(undoStack.size() - 1);
        dirty = !undoStack.isEmpty();
        saveError = Collections.emptyList();
        saveNote = Collections.emptyList();
        refreshValidation();
        if (focus >= model.fields().size()) {
            focus = firstEditable();
        }
    }

    private void requestClose() {
        if (dirty) {
            confirmDiscard = true;
        } else {
            closed = true;
            saved = false;
        }
    }

    /**
     * 保存第一步：校验 → 渲染/合并注释 → 算 diff，进入预览。
     *
     * <p>预览是唯一的确认面：注释将丢失、文件被外部改动都在这里显示；{@code y} 才落盘。
     * 预览读取的是「此刻的磁盘内容」，所以 diff 是真实的；落盘前 {@link SaveFlow#commit}
     * 再验一次指纹，预览之后文件又被改则拒绝写。</p>
     */
    private void save() {
        refreshValidation();
        try {
            FileStamp current = FileStamp.of(yamlFile);
            SaveFlow.Result result = SaveFlow.prepare(model, yamlFile, baseline);
            if (!result.ok()) {
                saveError = result.violations;
                saveNote = Collections.emptyList();
                focusFirst(result.violations);
                return;
            }
            pendingExternal = baseline != null && !baseline.matches(current);
            baseline = current;
            pending = result;
            previewScroll = 0;
            saveError = Collections.emptyList();
            saveNote = result.orphanNotes;
            mode = Mode.CONFIRM_SAVE;
        } catch (IOException e) {
            saveError = Collections.singletonList(
                    new Violation("", "cannot read file: " + messageOf(e)));
            saveNote = Collections.emptyList();
        }
    }

    /** 预览态：{@code y} 写入，{@code n/esc} 回到表单，方向键滚动 diff。 */
    private void handlePreview(NavKey key, char ch) {
        switch (key) {
            case CHAR:
                if (ch == 'y' || ch == 'Y') {
                    commitPending();
                } else if (ch == 'n' || ch == 'N') {
                    cancelPreview();
                }
                return;
            case ESC:
            case QUIT:
                cancelPreview();
                return;
            case UP:
                previewScroll = Math.max(0, previewScroll - 1);
                return;
            case DOWN:
                previewScroll = Math.min(maxPreviewScroll(), previewScroll + 1);
                return;
            case PGUP:
                previewScroll = Math.max(0, previewScroll - 10);
                return;
            case PGDN:
                previewScroll = Math.min(maxPreviewScroll(), previewScroll + 10);
                return;
            default:
                return;
        }
    }

    private void commitPending() {
        if (pending == null) {
            mode = Mode.BROWSE;
            return;
        }
        SaveFlow.Result result = pending;
        try {
            // 始终带预览时的指纹：预览之后文件再被改，就拒绝写。
            SaveFlow.commit(yamlFile, result.newText, baseline);
        } catch (IOException e) {
            pending = null;
            mode = Mode.BROWSE;
            saveError = Collections.singletonList(
                    new Violation("", "cannot save: " + messageOf(e)));
            saveNote = Collections.emptyList();
            return;
        }
        saveNote = successNote(result);
        saveError = Collections.emptyList();
        pending = null;
        dirty = false;
        saved = true;
        closed = true;
    }

    private void cancelPreview() {
        pending = null;
        previewScroll = 0;
        saveNote = Collections.emptyList();
        mode = Mode.BROWSE;
    }
    private static List<String> successNote(SaveFlow.Result result) {
        if (result.lostComments <= 0) {
            return Collections.emptyList();
        }
        StringBuilder note = new StringBuilder();
        note.append(result.lostComments).append(" comment(s) were dropped");
        if (result.exampleComments > 0) {
            note.append("; ").append(result.exampleComments)
                    .append(" commented-out example")
                    .append(result.exampleComments == 1 ? "" : "s");
        }
        return Collections.singletonList(note.toString());
    }

    private void focusFirst(List<Violation> violations) {
        for (Violation violation : violations) {
            EditorField field = findField(ownerPath(violation.path));
            if (field != null) {
                focus = model.fields().indexOf(field);
                return;
            }
        }
    }

    private void refreshValidation() {
        validation = model.validate();
    }

    /**
     * 把带后缀的校验路径归到可编辑字段：{@code runtime.opts[0]} → {@code runtime.opts}，
     * {@code env.FOO} → {@code env}，{@code path-links[0]} → {@code path-links}，
     * {@code properties.K} → {@code properties}；精确命中的字段原样返回。
     */
    private String ownerPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (findField(path) != null) {
            return path;
        }
        String candidate = path;
        while (true) {
            int cut = Math.max(candidate.lastIndexOf('.'), candidate.lastIndexOf('['));
            if (cut <= 0) {
                return path;
            }
            candidate = candidate.substring(0, cut);
            if (findField(candidate) != null) {
                return candidate;
            }
        }
    }

    private EditorField findField(String path) {
        try {
            return model.field(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // -------------------------------------------------------------- render

    /**
     * 渲染整屏：字段表单（或行编辑）→ 状态行 → 错误/提示行。
     * {@code rows <= 0} 表示不限高，测试直接取全量。
     */
    public List<String> lines(int width, int rows) {
        int w = Math.max(1, width);
        List<String> out = new ArrayList<String>();
        if (mode == Mode.CONFIRM_SAVE) {
            out.addAll(previewLines(w));
        } else if (mode == Mode.EDIT_LINES) {
            appendLinesMode(out, w);
        } else {
            String editingPath = mode == Mode.EDIT_SCALAR ? current().path : null;
            out.addAll(EditorView.lines(model, focus, w, errorsByPath(), editingPath,
                    mode == Mode.EDIT_SCALAR ? buffer : null,
                    mode == Mode.EDIT_SCALAR ? cursor : 0));
            out.add(clip(statusLine(), w));
            for (String note : noteLines()) {
                out.add(clip(note, w));
            }
        }
        if (mode == Mode.CONFIRM_SAVE && previewScroll > 0 && out.size() > 0) {
            int from = Math.min(previewScroll, out.size() - 1);
            out = new ArrayList<String>(out.subList(from, out.size()));
        }
        int limit = rows <= 0 ? 0 : rows;
        if (limit > 0 && out.size() > limit) {
            int keep = Math.max(0, limit - 1);
            List<String> cut = new ArrayList<String>(out.subList(0, keep));
            cut.add(clip("... (" + (out.size() - keep) + " more)", w));
            return cut;
        }
        return out;
    }

    /** 保存预览：标题 + 警告 + 行级 diff + 键提示。 */
    private List<String> previewLines(int width) {
        List<String> out = new ArrayList<String>();
        int adds = 0;
        int dels = 0;
        for (String line : pending.diff) {
            if (line.startsWith(LineDiff.INSERT)) {
                adds++;
            } else if (line.startsWith(LineDiff.DELETE)) {
                dels++;
            }
        }
        out.add(clip("save preview  (+" + adds + " -" + dels + ")", width));
        if (pending.losesComments()) {
            out.add(clip("! " + pending.lostComments + " comment(s) will be dropped", width));
        }
        if (pendingExternal) {
            out.add(clip("! file changed outside since this screen opened", width));
        }
        for (String note : pending.orphanNotes) {
            out.add(clip("note: " + note, width));
        }
        out.addAll(hunks(pending.diff, width));
        out.add(clip("y save  n/esc cancel  up/down scroll", width));
        return out;
    }

    /**
     * 只显示改动行及其上下各一行，未改动的行折叠成 {@code ...}，避免整篇模板注释把改动埋掉。
     */
    private static List<String> hunks(List<String> diff, int width) {
        int size = diff.size();
        boolean[] keep = new boolean[size];
        int changes = 0;
        for (int i = 0; i < size; i++) {
            if (isChange(diff.get(i))) {
                changes++;
                keep[Math.max(0, i - 1)] = true;
                keep[i] = true;
                keep[Math.min(size - 1, i + 1)] = true;
            }
        }
        List<String> out = new ArrayList<String>();
        if (changes == 0) {
            out.add(clip("  (no changes)", width));
            return out;
        }
        boolean skipping = false;
        int skipped = 0;
        for (int i = 0; i < size; i++) {
            if (keep[i]) {
                if (skipping) {
                    out.add(clip("  ... (" + skipped + " unchanged line"
                            + (skipped == 1 ? "" : "s") + ")", width));
                    skipping = false;
                    skipped = 0;
                }
                out.add(clip(diff.get(i), width));
            } else {
                skipping = true;
                skipped++;
            }
        }
        if (skipping) {
            out.add(clip("  ... (" + skipped + " unchanged line"
                    + (skipped == 1 ? "" : "s") + ")", width));
        }
        return out;
    }

    private static boolean isChange(String line) {
        return line.startsWith(LineDiff.INSERT) || line.startsWith(LineDiff.DELETE);
    }

    private int maxPreviewScroll() {
        if (pending == null) {
            return 0;
        }
        return Math.max(0, pending.diff.size() + 4 - 10);
    }
    private void appendLinesMode(List<String> out, int width) {
        EditorField field = current();
        int size = field.values.size();
        boolean map = field.type == Schema.Type.STRING_MAP;
        out.add(clip("rows: " + field.path + "  (" + size + " row"
                + (size == 1 ? "" : "s") + ")", width));
        int visible = Math.min(size, MAX_ROW_WINDOW);
        int start = 0;
        if (visible > 0) {
            start = Math.max(0, Math.min(lineIndex - visible / 2, size - visible));
        }
        out.add(clip("   #  " + (map ? pad("key", KEY_WIDTH) + "  " : "")
                + pad("value", VALUE_WIDTH) + "  comment", width));
        for (int i = start; i < start + visible; i++) {
            String mark = i == lineIndex ? "> " : "  ";
            out.add(clip(mark + pad(String.valueOf(i + 1), 2) + "  "
                    + rowCells(field, i, map), width));
        }
        if (size == 0) {
            out.add(clip("  (no rows)", width));
        }
        out.add(clip("up/down select  enter edit  " + (map ? "k edit key  " : "")
                + "a add  x delete  J/K move  esc back", width));
    }

    /** 一行两列（映射是 key/value）加行尾注释；超宽由外层 clip 截断。 */
    private static String rowCells(EditorField field, int index, boolean map) {
        String row = field.values.get(index);
        String key = "";
        String value = row;
        if (map) {
            int eq = row.indexOf('=');
            key = eq < 0 ? row : row.substring(0, eq);
            value = eq < 0 ? "" : row.substring(eq + 1);
        }
        String comment = field.rowComments.get(map ? key : row);
        return (map ? pad(key, KEY_WIDTH) + "  " : "")
                + pad(value, VALUE_WIDTH) + "  " + (comment == null ? "" : comment);
    }

    private static String pad(String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() >= width) {
            return value;
        }
        StringBuilder out = new StringBuilder(value);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }
    private String statusLine() {
        if (confirmDiscard) {
            return "discard unsaved changes?  y discard  n keep";
        }
        String modeText;
        if (mode == Mode.EDIT_SCALAR) {
            modeText = "edit value (left/right move, home/end jump)";
        } else if (mode == Mode.EDIT_LINES) {
            modeText = "edit rows";
        } else if (mode == Mode.CONFIRM_SAVE) {
            modeText = "save preview";
        } else {
            modeText = "browse";
        }
        return "mode: " + modeText + "  field: " + current().path;
    }

    private List<String> noteLines() {
        List<String> source = new ArrayList<String>();
        for (Violation violation : saveError) {
            source.add("! " + violation.toString());
        }
        for (String note : saveNote) {
            source.add("note: " + note);
        }
        List<String> out = new ArrayList<String>();
        int shown = Math.min(source.size(), MAX_ERROR_LINES);
        for (int i = 0; i < shown; i++) {
            out.add(source.get(i));
        }
        if (source.size() > shown) {
            out.add("... (" + (source.size() - shown) + " more)");
        }
        return out;
    }

    private Map<String, String> errorsByPath() {
        if (validation.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> errors = new LinkedHashMap<String, String>();
        for (Violation violation : validation) {
            if (violation.path == null || violation.path.isEmpty()) {
                continue;
            }
            String owner = ownerPath(violation.path);
            if (owner != null && !errors.containsKey(owner)) {
                errors.put(owner, violation.message);
            }
        }
        return errors;
    }

    private EditorField current() {
        return model.fields().get(focus);
    }

    private int firstEditable() {
        List<EditorField> fields = model.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).readOnly) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isMulti(EditorField field) {
        return field.type == Schema.Type.STRING_LIST || field.type == Schema.Type.STRING_MAP;
    }

    private static String clip(String text, int width) {
        return EditorView.clip(text, width);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
