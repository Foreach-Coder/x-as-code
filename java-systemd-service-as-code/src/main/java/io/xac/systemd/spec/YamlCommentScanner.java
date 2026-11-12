package io.xac.systemd.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描既有 {@code xac.yaml} 中的注释，并把它们挂回 {@link SpecWriter#render} 产出的规范文本。
 *
 * <p>扫描按行进行，维护一个「缩进 → 路径」栈，路径形如 {@code name}、{@code artifact.jar}、
 * {@code runtime.opts[-Xmx512m]}、{@code path-links[./logs:/applog/demo]}、{@code env.KEY}。
 * 列表项以<strong>值</strong>而不是下标作键，因此编辑器重排 {@code opts} / {@code path-links}
 * 之后注释仍跟着原来的元素；同一个值出现多次时，第一条注释保留，其余进入
 * {@link Comments#orphans}。</p>
 *
 * <p>只有引号外、且前面是空白（或行首）的 {@code #} 才算注释；{@code |}/{@code >} 块标量内部
 * 的 {@code #} 是标量内容，既不当作注释，也不计入 dropped。紧邻字段的连续注释行算前置注释，
 * 被空行隔开的注释块、文件末尾无字段可挂的注释进入 {@link Comments#orphans}。注释文本里的
 * 换行符（CR/LF/NEL/LS/PS）一律转义为 {@code \\r}/{@code \\n}/{@code \\x85}/{@code \\u2028}/
 * {@code \\u2029}，保证一条注释永远不会跨行。</p>
 *
 * <p>{@link #apply} 不生成也不重排任何配置行：它只在既有行前插入注释行、在行尾追加同行注释，
 * 所以结果仍能被 {@link SpecLoader} 读回与 {@code render} 等价的 spec。对同一份 {@link Comments}
 * 重复应用不会重复插入；对已合并文本重新 {@link #scan} 再 apply 同样稳定。</p>
 *
 * <p>{@link #scanTemplate} 解析随包分发的 {@code xac.yaml.template}（模板里被注释掉的示例段
 * 用作路径锚点），{@link #apply(String, Comments, Comments)} 以模板注释打底、用户注释覆盖
 * 同名路径。</p>
 */
public final class YamlCommentScanner {

    private static final String INLINE_PREFIX = "  #";
    private static final String LEADING_PREFIX = "# ";
    private static final String TEMPLATE_RESOURCE = "/xac.yaml.template";
    private static final String DUPLICATE_PREFIX = "duplicate comment for ";

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
        List<String> lines = splitLines(stripBom(yaml));
        Map<String, String> inline = new LinkedHashMap<String, String>();
        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        List<String> orphans = new ArrayList<String>();
        List<Pending> pending = new ArrayList<Pending>();
        Map<String, Integer> occurrences = new LinkedHashMap<String, Integer>();
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
            int occurrence = 0;
            if (resolved.path != null) {
                Integer previous = occurrences.get(resolved.path);
                occurrence = previous == null ? 1 : previous.intValue() + 1;
                occurrences.put(resolved.path, Integer.valueOf(occurrence));
            }

            if (!pending.isEmpty()) {
                if (resolved.path == null) {
                    flushOrphans(pending, orphans);
                } else if (occurrence > 1) {
                    duplicateBlock(leading, resolved.path, pending, orphans);
                } else {
                    addLeading(leading, resolved.path, pending, orphans);
                }
                pending.clear();
            }

            if (commentAt >= 0) {
                String text = commentText(line.substring(commentAt));
                if (resolved.path == null) {
                    orphans.add(orphan(index, text));
                } else if (occurrence > 1) {
                    orphans.add(orphan(index, duplicateMessage(resolved.path, text)));
                } else {
                    inline.put(resolved.path, text);
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
     * 读取随包分发的 {@code /xac.yaml.template}，把其中的说明性注释按路径抽出来作默认注释。
     *
     * <p>模板里没有真实值的示例段（{@code # env:}、{@code #   GREETING: hello world} 等）只作为
     * 路径锚点：锚点前的散文注释挂到该路径，锚点自身与被注释掉的示例行不进入结果。模板中的空行
     * 不打断注释块，这样文件头与段说明都能挂到紧随其后的字段/段上。</p>
     *
     * <p>返回的注释仍带 {@code {{name}}} 占位符；它只供包内测试使用，实际写盘请用
     * {@link #scanTemplate(String)} 让注释里的路径与服务名一致。</p>
     */
    static Comments scanTemplate() {
        return scanTemplateText(readTemplate());
    }

    /**
     * 与 {@link #scanTemplate()} 相同，但把注释里的 {@code {{name}}} 替换成真实服务名。
     *
     * <p>{@code init} 生成 {@code xac.yaml} 时也是这样替换的，因此首次保存时用户文件里的注释与
     * 模板注释逐字一致，不会被误判成无法归属而计入 {@code dropped}。</p>
     */
    public static Comments scanTemplate(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        return scanTemplateText(readTemplate().replace("{{name}}", name));
    }

    private static Comments scanTemplateText(String template) {
        List<String> lines = splitLines(stripBom(template));
        Map<String, String> inline = new LinkedHashMap<String, String>();
        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        List<String> orphans = new ArrayList<String>();
        Set<String> templateLines = new LinkedHashSet<String>();
        List<Pending> block = new ArrayList<Pending>();
        Deque<Frame> stack = new ArrayDeque<Frame>();
        int skipBelowAnchor = -1;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int indent = indentOf(line);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                String body = trimmed.substring(1);
                if (body.startsWith(" ")) {
                    body = body.substring(1);
                }
                body = body.trim();
                templateLines.add(sanitizeComment(body));
                if (skipBelowAnchor >= 0) {
                    if (indent > skipBelowAnchor || isExampleLine(body)) {
                        continue;
                    }
                    skipBelowAnchor = -1;
                }
                String anchor = anchorKey(body);
                if (anchor != null) {
                    while (!stack.isEmpty() && stack.peek().indent >= indent) {
                        stack.pop();
                    }
                    String path = stack.isEmpty()
                            ? anchor : stack.peek().path + '.' + anchor;
                    if (!block.isEmpty()) {
                        putLeading(leading, path, block);
                        block.clear();
                    }
                    skipBelowAnchor = indent;
                    continue;
                }
                block.add(new Pending(index, sanitizeComment(body)));
                continue;
            }

            skipBelowAnchor = -1;
            Resolved resolved = resolve(line, indent, stack);
            if (!block.isEmpty() && resolved.path != null) {
                putLeading(leading, resolved.path, block);
                block.clear();
            }
        }
        flushOrphans(block, orphans);
        return new Comments(inline, leading, orphans, templateLines);
    }

    /**
     * 以 {@code base} 打底、{@code override} 覆盖同名路径。
     *
     * <p>同行注释按路径覆盖；前置注释整块替换（不做逐行混合）；orphans 按 base、override 顺序拼接。
     * 典型用法是 {@code merge(scanTemplate(), scan(oldYaml))}。</p>
     */
    public static Comments merge(Comments base, Comments override) {
        if (base == null) {
            throw new NullPointerException("base");
        }
        if (override == null) {
            throw new NullPointerException("override");
        }
        Map<String, String> inline = new LinkedHashMap<String, String>(base.inline);
        inline.putAll(override.inline);
        Map<String, List<String>> leading =
                new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : base.leading.entrySet()) {
            leading.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        for (Map.Entry<String, List<String>> entry : override.leading.entrySet()) {
            leading.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        List<String> orphans = new ArrayList<String>(base.orphans);
        orphans.addAll(override.orphans);
        Set<String> templateLines = new LinkedHashSet<String>(base.templateLines);
        templateLines.addAll(override.templateLines);
        return new Comments(inline, leading, orphans, templateLines);
    }

    /**
     * 把注释挂回规范渲染文本。
     *
     * <p>前置注释插到目标行上方（缩进与目标行一致），同行注释追加为 {@code "  # text"}；
     * 渲染文本里找不到对应路径、或本身就是游离注释的条目计入 {@code dropped}，不写入结果。
     * 目标行已有行尾注释、或目标行上方已有相同前置注释时不会再插一遍，因此同一份
     * {@link Comments} 对已合并文本重复应用是稳定的。</p>
     *
     * <p>注释文本在写入前会做同行化转义（见类注释），避免 {@code Comments} 被程序化构造时
     * 用换行符把注释变成配置行。</p>
     */
    public static ApplyResult apply(String rendered, Comments comments) {
        if (comments == null) {
            throw new NullPointerException("comments");
        }
        return applyInternal(rendered, comments, null, null);
    }

    /**
     * 以 {@code template} 的默认注释打底、{@code source} 的同路径注释覆盖，再挂回渲染文本。
     *
     * <p>{@code dropped} 只统计 {@code source}（用户文件）中无法挂回的注释；模板里没有对应
     * 字段的默认注释（例如未启用的 {@code env}/{@code workdir} 段）不算丢失，也不计入提示。</p>
     */
    public static ApplyResult apply(String rendered, Comments source, Comments template) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (template == null) {
            throw new NullPointerException("template");
        }
        return applyInternal(rendered, merge(template, source), source, template);
    }

    private static ApplyResult applyInternal(String rendered, Comments comments,
                                             Comments userComments, Comments template) {
        if (rendered == null) {
            throw new NullPointerException("rendered");
        }
        List<String> lines = splitLines(rendered);
        List<LineInfo> infos = indexLines(lines);
        List<String> out = new ArrayList<String>(lines.size() + comments.total());
        Set<String> usedLeading = new HashSet<String>();
        Set<String> usedInline = new HashSet<String>();
        Set<String> keptLeading = new HashSet<String>();
        Set<String> keptInline = new HashSet<String>();
        int kept = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineInfo info = infos.get(i);

            if (info.path != null && !usedLeading.contains(info.path)) {
                List<String> block = comments.leading.get(info.path);
                if (block != null && !block.isEmpty()) {
                    if (alreadyPresent(out, info.indent, block)) {
                        kept += block.size();
                    } else {
                        String pad = spaces(info.indent);
                        for (String text : block) {
                            out.add(pad + leadingLine(text));
                            kept++;
                        }
                    }
                    usedLeading.add(info.path);
                    keptLeading.add(info.path);
                }
            }

            String merged = line;
            if (info.path != null && !usedInline.contains(info.path)) {
                String text = comments.inline.get(info.path);
                if (text != null) {
                    if (commentIndex(line) < 0) {
                        merged = line + inlineSuffix(text);
                    }
                    usedInline.add(info.path);
                    keptInline.add(info.path);
                    kept++;
                }
            }
            out.add(merged);
        }

        int dropped;
        if (userComments == null) {
            dropped = comments.total() - kept;
        } else {
            dropped = 0;
            for (String orphan : userComments.orphans) {
                if (template == null || !coveredByTemplate(orphanText(orphan), template)) {
                    dropped++;
                }
            }
            for (Map.Entry<String, String> entry : userComments.inline.entrySet()) {
                if (!keptInline.contains(entry.getKey())
                        && (template == null || !coveredByTemplate(entry.getValue(), template))) {
                    dropped++;
                }
            }
            for (Map.Entry<String, List<String>> entry : userComments.leading.entrySet()) {
                if (!keptLeading.contains(entry.getKey())) {
                    for (String line : entry.getValue()) {
                        if (template == null || !coveredByTemplate(line, template)) {
                            dropped++;
                        }
                    }
                }
            }
        }
        return new ApplyResult(join(out), kept, dropped);
    }

    private static boolean alreadyPresent(List<String> out, int indent, List<String> block) {
        if (out.size() < block.size()) {
            return false;
        }
        int start = out.size() - block.size();
        String pad = spaces(indent);
        for (int i = 0; i < block.size(); i++) {
            if (!out.get(start + i).equals(pad + leadingLine(block.get(i)))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------- scan helpers

    private static void addLeading(Map<String, List<String>> leading, String path,
                                   List<Pending> pending, List<String> orphans) {
        if (leading.containsKey(path)) {
            for (Pending line : pending) {
                orphans.add(orphan(line.line, duplicateMessage(path, line.text)));
            }
            return;
        }
        putLeading(leading, path, pending);
    }

    private static void duplicateBlock(Map<String, List<String>> leading, String path,
                                       List<Pending> pending, List<String> orphans) {
        for (Pending line : pending) {
            orphans.add(orphan(line.line, duplicateMessage(path, line.text)));
        }
    }

    private static void putLeading(Map<String, List<String>> leading, String path,
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

    private static String duplicateMessage(String path, String text) {
        return DUPLICATE_PREFIX + path + ": " + text;
    }

    /**
     * 用户文件里无法挂回的注释，如果只是模板原文（init 生成的注释块被空行冲散后就是这种形态），
     * 不算用户丢注释：模板打底会把它重新写出来。只做逐字比较，用户改动过的行一律算 dropped。
     *
     * <p>以 {@code key:} 开头的注释（例如文件末尾自写的 {@code # workdir: /opt/prod/work}）是
     * 被注释掉的字段示例，不是丢失的散文；它保留在 {@link Comments#orphans} 里供 UI 展示，
     * 但不计入 {@code dropped}。</p>
     */
    private static boolean coveredByTemplate(String text, Comments template) {
        if (text == null) {
            return false;
        }
        if (template.templateLines.contains(text)) {
            return true;
        }
        return anchorKey(text) != null;
    }

    private static String orphanText(String orphan) {
        int marker = orphan.indexOf(": ");
        return marker >= 0 ? orphan.substring(marker + 2) : orphan;
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
        String text = hash < 0 ? hashAndText.trim() : hashAndText.substring(hash + 1).trim();
        return sanitizeComment(text);
    }

    /**
     * 注释只能占一行：把 YAML 认识的所有换行符转成可见转义，避免程序化构造的
     * {@link Comments} 注入额外配置行。
     */
    private static String sanitizeComment(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\u0085':
                    out.append("\\x85");
                    break;
                case '\u2028':
                    out.append("\\u2028");
                    break;
                case '\u2029':
                    out.append("\\u2029");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    /**
     * 锚点下面的示例行：列表项或 {@code key: value} 形式，属于被注释掉的示例段而不是说明文字。
     */
    private static boolean isExampleLine(String body) {
        return body.startsWith("- ") || body.equals("-") || anchorKey(body) != null;
    }

    /**
     * 模板里被注释掉的键（{@code # env:}、{@code #   workdir: /opt/x}）只用来定位前面的
     * 散文注释；键本身不含空格，否则视为普通句子。
     */
    private static String anchorKey(String body) {        int colon = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ':' && (i + 1 == body.length() || body.charAt(i + 1) == ' ')) {
                colon = i;
                break;
            }
        }
        if (colon <= 0) {
            return null;
        }
        String key = body.substring(0, colon);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return null;
            }
        }
        return key;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String readTemplate() {
        InputStream stream = YamlCommentScanner.class.getResourceAsStream(TEMPLATE_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("missing classpath resource " + TEMPLATE_RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    out.append('\n');
                }
                out.append(line);
                first = false;
            }
            return out.toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + TEMPLATE_RESOURCE, e);
        }
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
            int commentAt = commentIndex(line);
            String content = commentAt >= 0 ? line.substring(0, commentAt) : line;
            Resolved resolved = resolve(content, indent, stack);
            if (resolved.blockHeader) {
                blockIndent = indent;
            }
            out.add(new LineInfo(indent, resolved.path));
        }
        return out;
    }

    private static String leadingLine(String text) {
        String safe = sanitizeComment(text);
        return safe.isEmpty() ? "#" : LEADING_PREFIX + safe;
    }

    private static String inlineSuffix(String text) {
        String safe = sanitizeComment(text);
        return safe.isEmpty() ? INLINE_PREFIX : INLINE_PREFIX + ' ' + safe;
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
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(text.substring(start, i));
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
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
