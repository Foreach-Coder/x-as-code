package io.xac.systemd.tui.edit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public enum Mode { BROWSE, EDIT_SCALAR, EDIT_LINES }

    /** Tab 跳转的九个节段起点：name, artifact, runtime, env, properties, workdir, path-links, logs, lifecycle。 */
    private static final int[] SEGMENTS = {0, 2, 3, 5, 6, 7, 8, 9, 10};

    private static final int MAX_ERROR_LINES = 3;
    private static final int MAX_ROW_WINDOW = 10;

    private final Path yamlFile;
    private FileStamp baseline;
    private final List<EditorModel> undoStack = new ArrayList<EditorModel>();

    private EditorModel model;
    private int focus;
    private Mode mode = Mode.BROWSE;
    private String buffer = "";
    private int lineIndex;
    private boolean rowEdit;
    private boolean dirty;
    private boolean closed;
    private boolean saved;
    private boolean confirmDiscard;
    /**
     * 上一次确认时的文件指纹与已确认的原因。确认只对当时看到的那份文件有效：文件再变，
     * 旧确认就作废，必须重新提示。
     */
    private FileStamp confirmedStamp;
    private boolean confirmLostComments;
    private boolean confirmExternal;
    private List<Violation> saveError = Collections.emptyList();
    private List<String> saveNote = Collections.emptyList();
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
                buffer = "";
                return;
            case ENTER:
                applyBuffer();
                return;
            case BACKSPACE:
                if (!buffer.isEmpty()) {
                    buffer = buffer.substring(0, buffer.offsetByCodePoints(buffer.length(), -1));
                }
                return;
            case CHAR:
                if (ch >= 0x20 && ch != 0x7f) {
                    buffer = buffer + ch;
                }
                return;
            default:
                return;
        }
    }

    private void applyBuffer() {
        EditorField field = current();
        if (rowEdit) {
            List<String> values = new ArrayList<String>(field.values);
            boolean changed;
            if (lineIndex >= 0 && lineIndex < values.size()) {
                changed = !buffer.equals(values.get(lineIndex));
                values.set(lineIndex, buffer);
            } else {
                values.add(buffer);
                lineIndex = values.size() - 1;
                changed = true;
            }
            if (changed) {
                mutate(field.path, values);
            }
            rowEdit = false;
            buffer = "";
            mode = Mode.EDIT_LINES;
            return;
        }
        String existing = field.values.isEmpty() ? "" : field.values.get(0);
        if (!buffer.equals(existing)) {
            mutate(field.path, Collections.singletonList(buffer));
        }
        buffer = "";
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
                if (field.values.isEmpty()) {
                    return;
                }
                lineIndex = Math.max(0, Math.min(lineIndex, field.values.size() - 1));
                buffer = field.values.get(lineIndex);
                rowEdit = true;
                mode = Mode.EDIT_SCALAR;
                return;
            case CHAR:
                if (ch == 'a') {
                    addRow(field);
                } else if (ch == 'x') {
                    deleteRow(field);
                }
                return;
            default:
                return;
        }
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
        buffer = current().values.isEmpty() ? "" : current().values.get(0);
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
        clearConfirm();
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
        clearConfirm();
        saveError = Collections.emptyList();
        saveNote = Collections.emptyList();
        refreshValidation();
        if (focus >= model.fields().size()) {
            focus = firstEditable();
        }
    }

    /** 放弃上一次确认：文件指纹变了或用户又改了模型，旧确认不再覆盖新情况。 */
    private void clearConfirm() {
        confirmedStamp = null;
        confirmLostComments = false;
        confirmExternal = false;
    }

    private void requestClose() {
        if (dirty) {
            confirmDiscard = true;
        } else {
            closed = true;
            saved = false;
        }
    }

    /** 保存：校验 → 渲染/合并注释 → （必要时二次确认）→ 原子写入。 */
    private void save() {
        refreshValidation();
        if (baseline == null) {
            try {
                baseline = FileStamp.of(yamlFile);
            } catch (IOException e) {
                saveError = Collections.singletonList(
                        new Violation("", "cannot read file: " + messageOf(e)));
                saveNote = Collections.emptyList();
                return;
            }
        }
        SaveFlow.Result result;
        try {
            result = SaveFlow.prepare(model, yamlFile, baseline);
        } catch (IOException e) {
            clearConfirm();
            saveError = Collections.singletonList(new Violation("", "cannot read file: " + messageOf(e)));
            saveNote = Collections.emptyList();
            return;
        }
        if (!result.ok()) {
            clearConfirm();
            saveError = result.violations;
            saveNote = Collections.emptyList();
            focusFirst(result.violations);
            return;
        }

        FileStamp current;
        try {
            current = FileStamp.of(yamlFile);
        } catch (IOException e) {
            clearConfirm();
            saveError = Collections.singletonList(new Violation("", "cannot read file: " + messageOf(e)));
            saveNote = Collections.emptyList();
            return;
        }

        boolean outside = !baseline.matches(current);
        boolean loses = result.losesComments();
        boolean stampStale = confirmedStamp != null && !confirmedStamp.matches(current);
        boolean needComments = loses && !confirmLostComments;
        boolean needOutside = outside && (!confirmExternal || stampStale);
        if (needComments || needOutside) {
            // 确认只覆盖「此刻看到的这份文件」：记下指纹，外部改动确认后把 baseline 前移，
            // 下一次 s 会重新读文件、重新出 diff；再变则 stampStale 让提示重来。
            confirmedStamp = current;
            confirmLostComments = loses;
            confirmExternal = outside;
            if (outside) {
                baseline = current;
            }
            saveError = Collections.singletonList(
                    new Violation("", confirmPrompt(loses, outside, result.lostComments)));
            saveNote = result.orphanNotes;
            return;
        }

        saveError = Collections.emptyList();
        try {
            // 始终带 baseline：写前再验一次，堵住确认之后、落盘之前的改动。
            SaveFlow.commit(yamlFile, result.newText, baseline);
        } catch (IOException e) {
            clearConfirm();
            saveError = Collections.singletonList(new Violation("", "cannot save: " + messageOf(e)));
            return;
        }
        saveNote = successNote(result);
        dirty = false;
        clearConfirm();
        saved = true;
        closed = true;
    }

    private static String confirmPrompt(boolean loses, boolean outside, int lostComments) {
        String comments = lostComments + " comment(s) will be dropped";
        if (loses && outside) {
            return comments + " and the file changed outside; press s again to confirm";
        }
        if (loses) {
            return comments + "; press s again to confirm";
        }
        return "file changed outside; press s again to overwrite";
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
        if (mode == Mode.EDIT_LINES) {
            appendLinesMode(out, w);
        } else {
            String editingPath = mode == Mode.EDIT_SCALAR ? current().path : null;
            out.addAll(EditorView.lines(model, focus, w, errorsByPath(), editingPath,
                    mode == Mode.EDIT_SCALAR ? buffer : null));
        }
        out.add(clip(statusLine(), w));
        for (String note : noteLines()) {
            out.add(clip(note, w));
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

    private void appendLinesMode(List<String> out, int width) {
        EditorField field = current();
        int size = field.values.size();
        out.add(clip("rows: " + field.path + "  (" + size + " row"
                + (size == 1 ? "" : "s") + ")", width));
        int visible = Math.min(size, MAX_ROW_WINDOW);
        int start = 0;
        if (visible > 0) {
            start = Math.max(0, Math.min(lineIndex - visible / 2, size - visible));
        }
        for (int i = start; i < start + visible; i++) {
            String mark = i == lineIndex ? "> " : "  ";
            out.add(clip(mark + (i + 1) + "> " + field.values.get(i), width));
        }
        if (size == 0) {
            out.add(clip("  (no rows)", width));
        }
        out.add(clip("up/down select  enter edit  a add  x delete  esc back", width));
    }

    private String statusLine() {
        if (confirmDiscard) {
            return "discard unsaved changes?  y discard  n keep";
        }
        String modeText;
        if (mode == Mode.EDIT_SCALAR) {
            modeText = "edit value";
        } else if (mode == Mode.EDIT_LINES) {
            modeText = "edit rows";
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
