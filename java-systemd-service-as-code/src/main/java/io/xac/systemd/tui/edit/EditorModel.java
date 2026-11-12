package io.xac.systemd.tui.edit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.Schema;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;

/**
 * Schema 驱动的编辑草稿：固定 14 个字段，在文本值与 {@link ServiceSpec} 之间双向转换。
 *
 * <p>模型不可变：{@link #with(String, List)} 返回副本，原对象与原先读到的
 * {@link EditorField} 都不受影响。{@link #toSpec()} 只做结构转换，非法文本抛
 * {@link IllegalArgumentException}（消息带字段路径与行号/值）；语义校验交给
 * {@link #validate()}，其路径与文案与 CLI 完全一致。</p>
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

    private EditorModel(Path packageDir, List<EditorField> fields) {
        this.packageDir = packageDir;
        this.fields = Collections.unmodifiableList(new ArrayList<EditorField>(fields));
        LinkedHashMap<String, EditorField> index = new LinkedHashMap<String, EditorField>();
        for (EditorField field : this.fields) {
            index.put(field.path, field);
        }
        this.byPath = Collections.unmodifiableMap(index);
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
        return new EditorModel(spec.packageDir, out);
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
        return new EditorModel(packageDir, copy);
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
        String jar = value("artifact.jar").trim();
        if (!jar.isEmpty()) {
            builder.jar(jar);
        }
        String java = value("runtime.java").trim();
        if (!java.isEmpty()) {
            builder.java(java);
        }
        builder.opts(listValue("runtime.opts"));
        builder.env(mapValue("env"));
        builder.properties(mapValue("properties"));
        String workdir = value("workdir").trim();
        if (!workdir.isEmpty()) {
            builder.workdir(workdir);
        }
        builder.pathLinks(linkValue("path-links"));
        String logFile = value("logs.file").trim();
        if (!logFile.isEmpty()) {
            builder.logFile(logFile);
        }
        builder.lifecycle(new Lifecycle(
                booleanValue("lifecycle.auto-start"),
                enumValue("lifecycle.restart"),
                integerValue("lifecycle.restart-sec"),
                integerValue("lifecycle.timeout-stop-sec")));
        return builder.build();
    }

    public List<Violation> validate() {
        return ValidationRunner.run(toSpec());
    }

    private String value(String path) {
        List<String> values = field(path).values;
        return values.isEmpty() ? "" : values.get(0);
    }

    private String enumValue(String path) {
        String raw = value(path).trim();
        return raw.isEmpty() ? null : raw;
    }

    private Boolean booleanValue(String path) {
        String raw = value(path).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(path + ": expected true or false, got '" + raw + "'");
    }

    private Integer integerValue(String path) {
        String raw = value(path).trim();
        if (raw.isEmpty()) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
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
            String item = raw == null ? "" : raw.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException(path + " line " + (i + 1)
                        + ": must not be empty");
            }
            out.add(item);
        }
        return out;
    }

    private List<String> linkValue(String path) {
        List<String> links = listValue(path);
        for (int i = 0; i < links.size(); i++) {
            if (PathLink.parse(links.get(i), packageDir) == null) {
                throw new IllegalArgumentException(path + " line " + (i + 1)
                        + ": must be source:target, with an absolute target starting with /");
            }
        }
        return links;
    }

    private LinkedHashMap<String, String> mapValue(String path) {
        List<String> rows = field(path).values;
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < rows.size(); i++) {
            int line = i + 1;
            String raw = rows.get(i);
            String row = raw == null ? "" : raw.trim();
            int equals = row.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(path + " line " + line
                        + ": expected KEY=VALUE, got '" + (raw == null ? "" : raw) + "'");
            }
            String key = row.substring(0, equals).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException(path + " line " + line
                        + ": key must not be empty");
            }
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(path + " line " + line
                        + ": duplicate key '" + key + "'");
            }
            out.put(key, row.substring(equals + 1));
        }
        return out;
    }
}
