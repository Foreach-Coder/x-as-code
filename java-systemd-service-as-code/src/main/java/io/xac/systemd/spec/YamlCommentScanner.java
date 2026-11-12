package io.xac.systemd.spec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描既有 {@code xac.yaml} 中的注释，并把它们挂回 {@link SpecWriter#render} 产出的规范文本。
 *
 * <p>扫描按行进行，维护一个「缩进 → 路径」栈，路径形如 {@code name}、{@code artifact.jar}、
 * {@code runtime.opts[-Xmx512m]}、{@code path-links[./logs:/applog/demo]}、{@code env.KEY}。
 * 列表项以<strong>值</strong>而不是下标作键，因此编辑器重排 {@code opts} / {@code path-links}
 * 之后注释仍跟着原来的元素。</p>
 *
 * <p>只有引号外、且前面是空白（或行首）的 {@code #} 才算注释；{@code |}/{@code >} 块标量内部
 * 的 {@code #} 是标量内容，既不当作注释，也不计入 dropped。紧邻字段的连续注释行算前置注释，
 * 被空行隔开的注释块、文件末尾无字段可挂的注释进入 {@link Comments#orphans}。</p>
 *
 * <p>{@link #apply} 不生成也不重排任何配置行：它只在既有行前插入注释行、在行尾追加同行注释，
 * 所以结果仍能被 {@link SpecLoader} 读回与 {@code render} 等价的 spec。重复应用稳定。</p>
 */
public final class YamlCommentScanner {

    private static final String INLINE_PREFIX = "  #";
    private static final String LEADING_PREFIX = "# ";

    private YamlCommentScanner() {
    }

    /**
     * 逐行扫描 yaml，抽取同行注释、前置注释块与无法归属的注释。
     *
     * @param yaml 原文，允许 CRLF；注释文本会 trim
     */
    public static Comments scan(String yaml) {
        if (yaml == null) {
            throw new NullPointerException("yaml");
        }
        List<String> lines = splitLines(yaml);
        Map<String, String> inline = new LinkedHashMap<String, String>();
        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        List<String> orphans = new ArrayList<String>();
        List<Pending> pending = new ArrayList<Pending>();
        Deque<Frame> stack = new ArrayDeque<Frame>();
        int blockIndent = -1;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int indent = indentOf(line);
            String trimmed = line.trim();

            if (blockIndent >= 0) {
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (indent > blockIndent) {
                    continue;
                }
                blockIndent = -1;
            }

            if (trimmed.isEmpty()) {
                flushOrphans(pending, orphans);
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                pending.add(new Pending(index, commentText(trimmed)));
                continue;
            }

            int commentAt = commentIndex(line);
            String content = commentAt >= 0 ? line.substring(0, commentAt) : line;
            Resolved resolved = resolve(content, indent, stack);

            if (!pending.isEmpty()) {
                if (resolved.path != null) {
                    addLeading(leading, resolved.path, pending);
                } else {
                    flushOrphans(pending, orphans);
                }
                pending.clear();
            }

            if (commentAt >= 0) {
                String text = commentText(line.substring(commentAt));
                if (resolved.path != null) {
                    inline.put(resolved.path, text);
                } else {
                    orphans.add(orphan(index, text));
                }
            }

            if (resolved.blockHeader) {
                blockIndent = indent;
            }
        }

        flushOrphans(pending, orphans);
        return new Comments(inline, leading, orphans);
    }

    /**
     * 把注释挂回规范渲染文本。
     *
     * <p>前置注释插到目标行上方（缩进与目标行一致），同行注释追加为 {@code "  # text"}；
     * 渲染文本里找不到对应路径、或本身就是游离注释的条目计入 {@code dropped}，不写入结果。</p>
     */
    public static ApplyResult apply(String rendered, Comments comments) {
        if (rendered == null) {
            throw new NullPointerException("rendered");
        }
        if (comments == null) {
            throw new NullPointerException("comments");
        }
        List<String> lines = splitLines(rendered);
        List<LineInfo> infos = indexLines(lines);
        List<String> out = new ArrayList<String>(lines.size() + comments.total());
        Set<String> usedLeading = new HashSet<String>();
        Set<String> usedInline = new HashSet<String>();
        int kept = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineInfo info = infos.get(i);

            if (info.path != null && !usedLeading.contains(info.path)) {
                List<String> block = comments.leading.get(info.path);
                if (block != null && !block.isEmpty()) {
                    String pad = spaces(info.indent);
                    for (String text : block) {
                        out.add(pad + leadingLine(text));
                        kept++;
                    }
                    usedLeading.add(info.path);
                }
            }

            String merged = line;
            if (info.path != null && !usedInline.contains(info.path)) {
                String text = comments.inline.get(info.path);
                if (text != null) {
                    merged = line + inlineSuffix(text);
                    usedInline.add(info.path);
                    kept++;
                }
            }
            out.add(merged);
        }

        return new ApplyResult(join(out), kept, comments.total() - kept);
    }

    // ------------------------------------------------------------- scan helpers

    private static void addLeading(Map<String, List<String>> leading, String path,
                                   List<Pending> pending) {
        List<String> block = new ArrayList<String>(pending.size());
        for (Pending line : pending) {
            block.add(line.text);
        }
        List<String> existing = leading.get(path);
        if (existing == null) {
            leading.put(path, block);
        } else {
            existing.addAll(block);
        }
    }

    private static void flushOrphans(List<Pending> pending, List<String> orphans) {
        for (Pending line : pending) {
            orphans.add(orphan(line.line, line.text));
        }
        pending.clear();
    }

    private static String orphan(int zeroBasedLine, String text) {
        return "line " + (zeroBasedLine + 1) + ": " + text;
    }

    private static Resolved resolve(String content, int indent, Deque<Frame> stack) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return Resolved.NONE;
        }
        while (!stack.isEmpty() && stack.peek().indent >= indent) {
            stack.pop();
        }
        if (trimmed.charAt(0) == '-'
                && (trimmed.length() == 1 || Character.isWhitespace(trimmed.charAt(1)))) {
            if (stack.isEmpty()) {
                return Resolved.NONE;
            }
            String item = unquote(trimmed.substring(1).trim());
            return new Resolved(stack.peek().path + '[' + item + ']', false);
        }
        int colon = mappingColon(trimmed);
        if (colon < 0) {
            return Resolved.NONE;
        }
        String key = unquote(trimmed.substring(0, colon).trim());
        String value = trimmed.substring(colon + 1).trim();
        String path = stack.isEmpty() ? key : stack.peek().path + '.' + key;
        if (value.isEmpty()) {
            stack.push(new Frame(indent, path));
        }
        return new Resolved(path, isBlockIndicator(value));
    }

    private static int mappingColon(String text) {
        boolean single = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (doubleQuoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (single) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        single = false;
                    }
                }
                continue;
            }
            if ((c == '"' || c == '\'') && tokenStart(text, i)) {
                if (c == '"') {
                    doubleQuoted = true;
                } else {
                    single = true;
                }
                continue;
            }
            if (c == ':' && (i + 1 == text.length()
                    || Character.isWhitespace(text.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static int commentIndex(String line) {
        boolean single = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (doubleQuoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (single) {
                if (c == '\'') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        single = false;
                    }
                }
                continue;
            }
            if ((c == '"' || c == '\'') && tokenStart(line, i)) {
                if (c == '"') {
                    doubleQuoted = true;
                } else {
                    single = true;
                }
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 引号只有出现在 token 起始处才开启引用：{@code it's} 里的撇号是普通字符，
     * 而 {@code key: "a # b"} 里的引号才是 YAML 引号。
     */
    private static boolean tokenStart(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == ':' || c == '[' || c == '{' || c == ',' || c == '-';
        }
        return true;
    }

    private static boolean isBlockIndicator(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first != '|' && first != '>') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '+' && c != '-' && !Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static String commentText(String hashAndText) {
        int hash = hashAndText.indexOf('#');
        return hash < 0 ? hashAndText.trim() : hashAndText.substring(hash + 1).trim();
    }

    private static String unquote(String text) {
        if (text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if (first == '"' && last == '"') {
            int limit = text.length() - 1;
            StringBuilder out = new StringBuilder(limit);
            for (int i = 1; i < limit; i++) {
                char c = text.charAt(i);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                i++;
                if (i >= limit) {
                    break;
                }
                char escape = text.charAt(i);
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'x': {
                        int hex = readHex(text, i + 1, 2, limit);
                        if (hex < 0) {
                            out.append(escape);
                        } else {
                            out.append((char) hex);
                            i += 2;
                        }
                        break;
                    }
                    case 'u': {
                        int code = readHex(text, i + 1, 4, limit);
                        if (code < 0) {
                            out.append(escape);
                        } else {
                            out.append((char) code);
                            i += 4;
                        }
                        break;
                    }
                    default:
                        out.append(escape);
                        break;
                }
            }
            return out.toString();
        }
        if (first == '\'' && last == '\'') {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    private static int readHex(String text, int from, int count, int limit) {
        if (from + count > limit) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < count; i++) {
            int digit = Character.digit(text.charAt(from + i), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    // ------------------------------------------------------------ apply helpers

    private static List<LineInfo> indexLines(List<String> lines) {
        List<LineInfo> out = new ArrayList<LineInfo>(lines.size());
        Deque<Frame> stack = new ArrayDeque<Frame>();
        int blockIndent = -1;
        for (String line : lines) {
            int indent = indentOf(line);
            String trimmed = line.trim();
            if (blockIndent >= 0) {
                if (trimmed.isEmpty()) {
                    out.add(LineInfo.NONE);
                    continue;
                }
                if (indent > blockIndent) {
                    out.add(LineInfo.NONE);
                    continue;
                }
                blockIndent = -1;
            }
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                out.add(LineInfo.NONE);
                continue;
            }
            Resolved resolved = resolve(line, indent, stack);
            if (resolved.blockHeader) {
                blockIndent = indent;
            }
            out.add(new LineInfo(indent, resolved.path));
        }
        return out;
    }

    private static String leadingLine(String text) {
        return text.isEmpty() ? "#" : LEADING_PREFIX + text;
    }

    private static String inlineSuffix(String text) {
        return text.isEmpty() ? INLINE_PREFIX : INLINE_PREFIX + ' ' + text;
    }

    private static String spaces(int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(' ');
        }
        return out.toString();
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length()
                && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
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

    // ------------------------------------------------------------------ values

    /** {@link #apply} 的结果：合并后的文本、成功挂回的注释行数、无法挂回的注释行数。 */
    public static final class ApplyResult {

        public final String text;
        public final int kept;
        public final int dropped;

        ApplyResult(String text, int kept, int dropped) {
            this.text = text;
            this.kept = kept;
            this.dropped = dropped;
        }

        public boolean hasDropped() {
            return dropped > 0;
        }

        @Override
        public String toString() {
            return "ApplyResult{kept=" + kept + ", dropped=" + dropped + '}';
        }
    }

    private static final class Frame {
        final int indent;
        final String path;

        Frame(int indent, String path) {
            this.indent = indent;
            this.path = path;
        }
    }

    private static final class Pending {
        final int line;
        final String text;

        Pending(int zeroBasedLine, String text) {
            this.line = zeroBasedLine;
            this.text = text;
        }
    }

    private static final class Resolved {
        static final Resolved NONE = new Resolved(null, false);

        final String path;
        final boolean blockHeader;

        Resolved(String path, boolean blockHeader) {
            this.path = path;
            this.blockHeader = blockHeader;
        }
    }

    private static final class LineInfo {
        static final LineInfo NONE = new LineInfo(0, null);

        final int indent;
        final String path;

        LineInfo(int indent, String path) {
            this.indent = indent;
            this.path = path;
        }
    }
}
