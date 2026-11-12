package io.xac.systemd.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 把 {@link ServiceSpec} 规范化写回 xac.yaml。
 *
 * <p>输出是纯文本、确定性、不含注释：段序固定为
 * {@code name} → {@code description} → {@code artifact.jar} → {@code runtime.java} →
 * {@code runtime.opts} → {@code env} → {@code properties} → {@code workdir} →
 * {@code path-links} → {@code logs} → {@code lifecycle}；{@code env}/{@code properties}
 * 的键按字符串自然序排序，{@code opts} 与 {@code path-links} 保持原有顺序；换行统一为
 * LF，结尾恰好一个换行。注释由编辑器的注释扫描器在渲染结果之上合并，这里不负责。</p>
 *
 * <p>缺省与省略：{@code description} 为空则整行省略；{@code opts} 为空写 {@code opts: []}，
 * 让「没有 JVM 参数」在文本里可见；{@code env}/{@code properties} 为空则整段省略，与
 * {@code xac.yaml.template} 的默认形态一致；{@code workdir}、{@code logs.file} 为空则省略；
 * {@code lifecycle} 恒写四键，未设置的值用 {@link Lifecycle} 的文档默认值。</p>
 *
 * <p>标量按需加双引号：空串、首尾空白、含 {@code :} 或 {@code #} 或 {@code ${}、含控制字符、
 * 形似 YAML 布尔/空值/数字/日期、或以 YAML 指示符开头时加引号，并转义 {@code \\}、
 * {@code "}、换行、制表符等控制字符；不使用 block 标量。这样同一个值读回
 * {@link SpecLoader} 后仍是同一个字符串。</p>
 *
 * <p>路径（{@code artifact.jar}、{@code workdir}）相对包目录渲染，分隔符统一成 {@code /}；
 * 与包目录不在同一个根（例如 Windows 上换了盘符）时退回绝对路径。</p>
 */
public final class SpecWriter {

    private static final String INDENT = "  ";

    /** 这些标量在 YAML 里会被读成布尔或空值，作为字符串时必须加引号。 */
    private static final Set<String> QUOTED_WORDS = new HashSet<String>(Arrays.asList(
            "true", "True", "TRUE", "false", "False", "FALSE",
            "null", "Null", "NULL", "~"));

    /** 形似整型/浮点/十六进制/六十进制（YAML 1.1 的 12:30 会读成 750）。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:0[bB][01_]+|0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[0-7_]+"
                    + "|(?:0|[1-9][0-9_]*)(?:\\.[0-9_]*)?(?:[eE][-+]?[0-9]+)?"
                    + "|\\.[0-9][0-9_]*(?:[eE][-+]?[0-9]+)?"
                    + "|\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))"
                    + "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+(?:\\.[0-9_]*)?");

    /** 形似 YAML 时间戳；本仓库的解析器不认它，但别的工具会。 */
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private static final String LEADING_INDICATORS = "[]{},&*!|>'\"%@`";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SpecWriter() {
    }

    /**
     * 渲染规范化的 xac.yaml 文本。
     *
     * @param spec 已经过 Schema 与验证器的服务定义
     * @return 以单个 {@code \n} 结尾的文本，换行一律为 LF
     */
    public static String render(ServiceSpec spec) {
        StringBuilder out = new StringBuilder(256);

        out.append("name: ").append(scalar(spec.name)).append('\n');
        if (spec.description != null && !spec.description.isEmpty()) {
            out.append("description: ").append(scalar(spec.description)).append('\n');
        }

        blankLine(out);
        out.append("artifact:\n")
                .append(INDENT).append("jar: ")
                .append(scalar(relativePath(spec.jar, spec.packageDir))).append('\n');

        blankLine(out);
        out.append("runtime:\n")
                .append(INDENT).append("java: ").append(scalar(spec.java)).append('\n');
        if (spec.opts == null || spec.opts.isEmpty()) {
            out.append(INDENT).append("opts: []\n");
        } else {
            out.append(INDENT).append("opts:\n");
            for (String opt : spec.opts) {
                out.append(INDENT).append(INDENT).append("- ").append(scalar(opt)).append('\n');
            }
        }

        if (spec.env != null && !spec.env.isEmpty()) {
            blankLine(out);
            out.append("env:\n");
            appendMap(out, spec.env);
        }

        if (spec.properties != null && !spec.properties.isEmpty()) {
            blankLine(out);
            out.append("properties:\n");
            appendMap(out, spec.properties);
        }

        if (spec.workdir != null) {
            blankLine(out);
            out.append("workdir: ")
                    .append(scalar(relativePath(spec.workdir, spec.packageDir))).append('\n');
        }

        if (spec.pathLinks != null && !spec.pathLinks.isEmpty()) {
            blankLine(out);
            out.append("path-links:\n");
            for (String link : spec.pathLinks) {
                out.append(INDENT).append("- ").append(scalar(link)).append('\n');
            }
        }

        if (spec.logFile != null) {
            blankLine(out);
            out.append("logs:\n")
                    .append(INDENT).append("file: ").append(scalar(spec.logFile)).append('\n');
        }

        Lifecycle lifecycle = spec.lifecycle == null
                ? new Lifecycle(null, null, null, null)
                : spec.lifecycle;
        blankLine(out);
        out.append("lifecycle:\n")
                .append(INDENT).append("auto-start: ")
                .append(lifecycle.autoStartOrDefault()).append('\n')
                .append(INDENT).append("restart: ")
                .append(scalar(lifecycle.restartOrDefault())).append('\n')
                .append(INDENT).append("restart-sec: ")
                .append(lifecycle.restartSecOrDefault()).append('\n')
                .append(INDENT).append("timeout-stop-sec: ")
                .append(lifecycle.timeoutStopSecOrDefault()).append('\n');

        return out.toString();
    }

    /**
     * 原子地把渲染结果写到 {@code file}：先在同目录创建 {@code .xac-*.tmp}，设 0644
     * （POSIX；其他文件系统跳过），再用 {@code ATOMIC_MOVE} 覆盖目标。
     *
     * <p>父目录必须已存在，否则抛 {@link IOException}，目标文件保持原样。失败时临时文件被
     * 删除，目标文件不会被部分写入。这里不做备份，也不做 diff/确认——那是编辑器的职责。</p>
     *
     * @param file 目标文件（通常是 {@code <package>/xac.yaml}）
     * @param yaml 完整的新内容
     * @throws IOException 父目录不存在、临时文件无法创建或最终替换失败
     */
    public static void save(Path file, String yaml) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file is null");
        }
        if (yaml == null) {
            throw new IllegalArgumentException("yaml is null");
        }
        Path target = file.toAbsolutePath().normalize();
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("no parent directory: " + target);
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("parent directory does not exist: " + dir);
        }

        Path temp = Files.createTempFile(dir, ".xac-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, yaml.getBytes(StandardCharsets.UTF_8));
            setReadableMode(temp);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                // 保留原始异常：清理失败最多留下一个临时文件，不掩盖写入错误。
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    // -------------------------------------------------------------- internals

    private static void blankLine(StringBuilder out) {
        if (out.length() > 0) {
            out.append('\n');
        }
    }

    private static void appendMap(StringBuilder out, Map<String, String> values) {
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(values).entrySet()) {
            out.append(INDENT)
                    .append(scalar(entry.getKey())).append(": ")
                    .append(scalar(entry.getValue())).append('\n');
        }
    }

    /** 相对包目录的路径文本，分隔符统一成 {@code /}；跨根时退回绝对路径。 */
    private static String relativePath(Path path, Path packageDir) {
        if (path == null) {
            return "";
        }
        Path target = path.toAbsolutePath().normalize();
        Path root = packageDir == null ? null : packageDir.toAbsolutePath().normalize();
        String text = target.toString();
        if (root != null) {
            try {
                Path relative = root.relativize(target);
                if (relative.toString().length() > 0) {
                    text = relative.toString();
                }
            } catch (IllegalArgumentException e) {
                // 不同根（Windows 盘符）无法相对化，用绝对路径。
                text = target.toString();
            }
        }
        return text.replace('\\', '/');
    }

    /**
     * 把一个字符串渲染成 YAML 标量：能安全用 plain 形式就不加引号，否则用双引号并转义。
     */
    static String scalar(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "\"\"";
        }
        if (!needsQuotes(raw)) {
            return raw;
        }
        StringBuilder quoted = new StringBuilder(raw.length() + 2);
        quoted.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        quoted.append("\\x").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else if (isUnicodeLineBreak(c)) {
                        // YAML 把 NEL/LS/PS 当换行；plain 标量里会被折叠成空格，必须转义。
                        quoted.append("\\u")
                                .append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf])
                                .append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        quoted.append(c);
                    }
            }
        }
        quoted.append('"');
        return quoted.toString();
    }

    private static boolean needsQuotes(String raw) {
        if (!raw.equals(raw.trim())) {
            return true;
        }
        if (raw.indexOf(':') >= 0 || raw.indexOf('#') >= 0 || raw.contains("${")) {
            return true;
        }
        if (QUOTED_WORDS.contains(raw)) {
            return true;
        }
        if (NUMBER.matcher(raw).matches() || DATE.matcher(raw).matches()) {
            return true;
        }
        if (startsWithIndicator(raw)) {
            return true;
        }
        return hasControlCharacter(raw);
    }

    private static boolean startsWithIndicator(String raw) {
        char first = raw.charAt(0);
        if (LEADING_INDICATORS.indexOf(first) >= 0) {
            return true;
        }
        if (first == '-' || first == '?' || first == ':') {
            // 只有紧跟空格（或自身就是整个值）时才是 YAML 指示符；"-Xmx512m" 仍是普通标量。
            return raw.length() == 1 || raw.charAt(1) == ' ';
        }
        return false;
    }

    private static boolean hasControlCharacter(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || isUnicodeLineBreak(c)) {
                return true;
            }
        }
        return false;
    }

    /** NEL (U+0085)、LINE SEPARATOR (U+2028)、PARAGRAPH SEPARATOR (U+2029)。 */
    private static boolean isUnicodeLineBreak(char c) {
        return c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static void setReadableMode(Path temp) throws IOException {
        if (!temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> mode = PosixFilePermissions.fromString("rw-r--r--");
        try {
            Files.setPosixFilePermissions(temp, mode);
        } catch (UnsupportedOperationException e) {
            // 声称支持 POSIX 却拒绝设置权限：保持临时文件的默认权限即可。
        }
    }
}
