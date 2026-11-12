package io.xac.systemd.tui.edit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.Schema;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;

/**
 * Schema 驱动的编辑草稿：固定 14 个字段，在文本值与 {@link ServiceSpec} 之间双向转换。
 *
 * <p>模型不可变：{@link #with(String, List)} 返回副本，原对象与原先读到的
 * {@link EditorField} 都不受影响。文本值是<strong>用户原文</strong>：不 trim、
 * 不改写外层空白，只有判空时才用 {@code trim().isEmpty()}。{@link #toSpec()} 只做
 * 结构转换，非法文本抛 {@link IllegalArgumentException}（消息以字段路径开头）；
 * {@link #validate()} 不抛异常，把必填缺失与转换失败都收敛成 {@link Violation}。</p>
 */
public final class EditorModel {

    /** {@code lifecycle.restart} 的取值；首项 {@code ""} 表示缺省（{@code on-failure}）。 */
    private static final List<String> RESTART_OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "", "no", "on-success", "on-failure", "on-abnormal",
            "on-abort", "on-watchdog", "always"));

    private static final LinkedHashMap<String, String> LABELS = new LinkedHashMap<String, String>();

    static {
        LABELS.put("name", "Name");
        LABELS.put("description", "Description");
        LABELS.put("artifact.jar", "Jar");
        LABELS.put("runtime.java", "Java");
        LABELS.put("runtime.opts", "JVM options");
        LABELS.put("env", "Environment");
        LABELS.put("properties", "Properties");
        LABELS.put("workdir", "Working directory");
        LABELS.put("path-links", "Path links");
        LABELS.put("logs.file", "Log file");
        LABELS.put("lifecycle.auto-start", "Auto start");
        LABELS.put("lifecycle.restart", "Restart policy");
        LABELS.put("lifecycle.restart-sec", "Restart delay (s)");
        LABELS.put("lifecycle.timeout-stop-sec", "Stop timeout (s)");
    }

    private final Path packageDir;
    private final List<EditorField> fields;
    private final Map<String, EditorField> byPath;

    /**
     * 载入时无法用 {@code KEY=VALUE} 行表示的 map 键（含 {@code =}）。
     * 路径 → 说明；用户重写该字段后清除。
     */
    private final Map<String, String> mapConflicts;

    private EditorModel(Path packageDir, List<EditorField> fields,
                        Map<String, String> mapConflicts) {
        this.packageDir = packageDir;
        this.fields = Collections.unmodifiableList(new ArrayList<EditorField>(fields));
        LinkedHashMap<String, EditorField> index = new LinkedHashMap<String, EditorField>();
        for (EditorField field : this.fields) {
            index.put(field.path, field);
        }
        this.byPath = Collections.unmodifiableMap(index);
        this.mapConflicts = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(mapConflicts));
    }

    // ------------------------------------------------------------ construction

    public static EditorModel from(ServiceSpec spec, Comments comments) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        Comments source = comments == null ? Comments.empty() : comments;
        Lifecycle lifecycle = spec.lifecycle == null
                ? new Lifecycle(null, null, null, null)
                : spec.lifecycle;
        List<String> opts = spec.opts == null ? Collections.<String>emptyList() : spec.opts;
        List<String> pathLinks = spec.pathLinks == null
                ? Collections.<String>emptyList()
                : spec.pathLinks;

        List<EditorField> out = new ArrayList<EditorField>(14);
        out.add(field("name", Schema.Type.STRING, true, single(spec.name),
                Collections.<String>emptyList(), source));
        out.add(field("description", Schema.Type.STRING, false,
                single(spec.description == null ? "" : spec.description),
                Collections.<String>emptyList(), source));
        out.add(field("artifact.jar", Schema.Type.STRING, false,
                single(pathText(spec.jar, spec.packageDir)),
                Collections.<String>emptyList(), source));
        out.add(field("runtime.java", Schema.Type.STRING, false,
                single(spec.java == null ? "" : spec.java),
                Collections.<String>emptyList(), source));
        out.add(field("runtime.opts", Schema.Type.STRING_LIST, false,
                new ArrayList<String>(opts), Collections.<String>emptyList(), source));
        out.add(field("env", Schema.Type.STRING_MAP, false,
                rows(spec.env), Collections.<String>emptyList(), source));
        out.add(field("properties", Schema.Type.STRING_MAP, false,
                rows(spec.properties), Collections.<String>emptyList(), source));
        out.add(field("workdir", Schema.Type.STRING, false,
                single(pathText(spec.workdir, spec.packageDir)),
                Collections.<String>emptyList(), source));
        out.add(field("path-links", Schema.Type.STRING_LIST, false,
                new ArrayList<String>(pathLinks), Collections.<String>emptyList(), source));
        out.add(field("logs.file", Schema.Type.STRING, false,
                single(spec.logFile == null ? "" : spec.logFile),
                Collections.<String>emptyList(), source));
        out.add(field("lifecycle.auto-start", Schema.Type.BOOLEAN, false,
                single(lifecycle.autoStart == null ? "" : lifecycle.autoStart.toString()),
                Collections.<String>emptyList(), source));
        out.add(field("lifecycle.restart", Schema.Type.STRING, false,
                single(lifecycle.restart == null ? "" : lifecycle.restart),
                RESTART_OPTIONS, source));
        out.add(field("lifecycle.restart-sec", Schema.Type.INTEGER, false,
                single(lifecycle.restartSec == null ? "" : lifecycle.restartSec.toString()),
                Collections.<String>emptyList(), source));
        out.add(field("lifecycle.timeout-stop-sec", Schema.Type.INTEGER, false,
                single(lifecycle.timeoutStopSec == null ? "" : lifecycle.timeoutStopSec.toString()),
                Collections.<String>emptyList(), source));

        LinkedHashMap<String, String> conflicts = new LinkedHashMap<String, String>();
        detectMapConflicts(conflicts, "env", spec.env);
        detectMapConflicts(conflicts, "properties", spec.properties);
        return new EditorModel(spec.packageDir, out, conflicts);
    }

    private static EditorField field(String path, Schema.Type type, boolean readOnly,
                                     List<String> values, List<String> options,
                                     Comments comments) {
        return new EditorField(path, type, readOnly, values, options,
                label(path), help(comments, path));
    }

    private static String label(String path) {
        String label = LABELS.get(path);
        return label == null ? path : label;
    }

    /** 同路径的连续前置注释优先，其次是同行注释；都没有时返回 {@code ""}。 */
    private static String help(Comments comments, String path) {
        List<String> leading = comments.leading.get(path);
        if (leading != null && !leading.isEmpty()) {
            return join(leading);
        }
        String inline = comments.inline.get(path);
        return inline == null ? "" : inline;
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private static List<String> single(String value) {
        return Collections.singletonList(value == null ? "" : value);
    }

    private static List<String> rows(Map<String, String> map) {
        List<String> out = new ArrayList<String>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        return out;
    }

    /** {@code KEY=VALUE} 行无法表示含 {@code =} 的键：载入时记下，写回时拒绝而不是静默拆键。 */
    private static void detectMapConflicts(Map<String, String> conflicts, String path,
                                           Map<String, String> map) {
        if (map == null) {
            return;
        }
        for (String key : map.keySet()) {
            if (key != null && key.indexOf('=') >= 0) {
                conflicts.put(path, "key '" + key
                        + "' contains '=', so it cannot be written as KEY=VALUE");
                return;
            }
        }
    }

    /** 包目录内用相对路径（正斜杠），包目录外保留绝对路径。 */
    private static String pathText(Path path, Path packageDir) {
        if (path == null) {
            return "";
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path root = packageDir.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            String relative = root.relativize(absolute).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return absolute.toString();
    }

    // ------------------------------------------------------------ accessors

    /** 固定顺序的不可变字段列表。 */
    public List<EditorField> fields() {
        return fields;
    }

    public EditorField field(String path) {
        EditorField found = byPath.get(path);
        if (found == null) {
            throw new IllegalArgumentException("unknown editor field '" + path + "'");
        }
        return found;
    }

    /** 返回副本：只替换目标字段的文本值，其余字段原样带过去。 */
    public EditorModel with(String path, List<String> values) {
        EditorField target = field(path);
        if (target.readOnly) {
            throw new IllegalArgumentException("field '" + path + "' is read-only");
        }
        checkArity(target, values);
        List<EditorField> copy = new ArrayList<EditorField>(fields.size());
        for (EditorField field : fields) {
            if (field.path.equals(path)) {
                copy.add(field.withValues(values));
            } else {
                copy.add(field);
            }
        }
        LinkedHashMap<String, String> conflicts = new LinkedHashMap<String, String>(mapConflicts);
        conflicts.remove(path);
        return new EditorModel(packageDir, copy, conflicts);
    }

    private static void checkArity(EditorField target, List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("field '" + target.path + "': values must not be null");
        }
        boolean multi = target.type == Schema.Type.STRING_LIST
                || target.type == Schema.Type.STRING_MAP;
        if (!multi && values.size() != 1) {
            throw new IllegalArgumentException("field '" + target.path
                    + "' expects exactly one value, got " + values.size());
        }
    }

    // ------------------------------------------------------------ conversion

    public ServiceSpec toSpec() {
        ServiceSpec.Builder builder = ServiceSpec.builder(packageDir);
        builder.name(value("name"));
        builder.description(value("description"));
        String jar = value("artifact.jar");
        if (!isBlank(jar)) {
            builder.jar(jar);
        }
        String java = value("runtime.java");
        if (!isBlank(java)) {
            builder.java(java);
        }
        builder.opts(listValue("runtime.opts"));
        builder.env(mapValue("env"));
        builder.properties(mapValue("properties"));
        String workdir = value("workdir");
        if (!isBlank(workdir)) {
            builder.workdir(workdir);
        }
        builder.pathLinks(linkValue("path-links"));
        String logFile = value("logs.file");
        if (!isBlank(logFile)) {
            builder.logFile(logFile);
        }
        builder.lifecycle(new Lifecycle(
                booleanValue("lifecycle.auto-start"),
                enumValue("lifecycle.restart"),
                integerValue("lifecycle.restart-sec"),
                integerValue("lifecycle.timeout-stop-sec")));
        return builder.build();
    }

    /**
     * 保存前全量校验：必填非空 + {@link ValidationRunner}。
     * 与 {@link #toSpec()} 不同，任何非法文本都转成 {@link Violation}，不抛异常。
     */
    public List<Violation> validate() {
        List<Violation> out = new ArrayList<Violation>();
        Set<String> requiredEmpties = new LinkedHashSet<String>();
        required(out, requiredEmpties, "name");
        required(out, requiredEmpties, "artifact.jar");
        required(out, requiredEmpties, "runtime.java");

        ServiceSpec spec;
        try {
            spec = toSpec();
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            String path = pathOf(message);
            out.add(new Violation(path, path.isEmpty() ? message : detailOf(message)));
            return out;
        }
        for (Violation violation : ValidationRunner.run(spec)) {
            // 空 name 由上面的必填检查负责，避免同一条错误出现两次。
            if (!requiredEmpties.contains(violation.path)) {
                out.add(violation);
            }
        }
        return out;
    }

    private void required(List<Violation> out, Set<String> paths, String path) {
        if (isBlank(value(path))) {
            out.add(new Violation(path, "must not be empty"));
            paths.add(path);
        }
    }

    /** 消息以字段路径开头时返回该路径（供 UI 定位），否则返回 {@code ""}。 */
    private String pathOf(String message) {
        int colon = message.indexOf(':');
        if (colon <= 0) {
            return "";
        }
        String head = message.substring(0, colon).trim();
        return byPath.containsKey(head) ? head : "";
    }

    private static String detailOf(String message) {
        int colon = message.indexOf(':');
        return message.substring(colon + 1).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String path) {
        List<String> values = field(path).values;
        return values.isEmpty() ? "" : values.get(0);
    }

    private String enumValue(String path) {
        String raw = value(path);
        return isBlank(raw) ? null : raw.trim();
    }

    private Boolean booleanValue(String path) {
        String raw = value(path);
        if (isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(path + ": expected true or false, got '" + raw + "'");
    }

    private Integer integerValue(String path) {
        String raw = value(path);
        if (isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + ": expected an integer, got '" + raw + "'");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(path + ": must be a positive integer, got '" + raw + "'");
        }
        return Integer.valueOf(parsed);
    }

    private List<String> listValue(String path) {
        List<String> rows = field(path).values;
        List<String> out = new ArrayList<String>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String raw = rows.get(i);
            if (isBlank(raw)) {
                throw new IllegalArgumentException(path + ": line " + (i + 1)
                        + ": must not be empty");
            }
            out.add(raw);
        }
        return out;
    }

    /**
     * 只做词法形状检查（最后一个冒号 + 绝对目标），不构造 {@link Path}：
     * 用户原文可能带 Windows 上非法的尾随空格，保存前由 {@code PathLinksValidator} 复核。
     */
    private List<String> linkValue(String path) {
        List<String> links = listValue(path);
        for (int i = 0; i < links.size(); i++) {
            String raw = links.get(i);
            int colon = raw.lastIndexOf(':');
            boolean shaped = colon > 0 && colon < raw.length() - 1
                    && raw.substring(colon + 1).startsWith("/");
            if (!shaped) {
                throw new IllegalArgumentException(path + ": line " + (i + 1)
                        + ": must be source:target, with an absolute target starting with /");
            }
        }
        return links;
    }

    private LinkedHashMap<String, String> mapValue(String path) {
        String conflict = mapConflicts.get(path);
        if (conflict != null) {
            throw new IllegalArgumentException(path + ": " + conflict);
        }
        List<String> rows = field(path).values;
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < rows.size(); i++) {
            int line = i + 1;
            String row = rows.get(i);
            int equals = row == null ? -1 : row.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(path + ": line " + line
                        + ": expected KEY=VALUE, got '" + (row == null ? "" : row) + "'");
            }
            String key = row.substring(0, equals);
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(path + ": line " + line
                        + ": duplicate key '" + key + "'");
            }
            out.put(key, row.substring(equals + 1));
        }
        return out;
    }
}
