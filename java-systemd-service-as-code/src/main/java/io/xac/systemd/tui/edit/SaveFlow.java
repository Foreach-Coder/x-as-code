package io.xac.systemd.tui.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.LineDiff;
import io.xac.systemd.spec.SpecWriter;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.spec.YamlCommentScanner;

/**
 * 一次「保存」的准备与落地：校验模型、把模型渲染成规范 yaml、把原文件里的注释挂回去、
 * 算出预览 diff，最后交给 {@link SpecWriter#save} 原子写入。
 *
 * <p>这里刻意不碰环境（不检查 jar、不查 root、不碰 systemd）——编辑器只负责把 yaml 写对，
 * 部署前的检查留给 apply。外部改动不在这里静默覆盖：调用方拿到 {@code modifiedOutside}
 * 后先提示一次，用户确认后再调 {@link #commit(Path, String, FileStamp)}，由它在写前
 * 再验一次指纹。</p>
 */
public final class SaveFlow {

    private SaveFlow() {
    }

    /** 保存准备的结果。{@code ok()} 为假时只有 {@code violations} 有意义。 */
    public static final class Result {

        public final List<Violation> violations;
        public final String newText;
        public final List<String> diff;
        public final List<String> orphanNotes;
        public final int lostComments;
        public final boolean modifiedOutside;

        Result(List<Violation> violations, String newText, List<String> diff,
               List<String> orphanNotes, int lostComments, boolean modifiedOutside) {
            this.violations = Collections.unmodifiableList(new ArrayList<Violation>(violations));
            this.newText = newText;
            this.diff = Collections.unmodifiableList(new ArrayList<String>(diff));
            this.orphanNotes = Collections.unmodifiableList(new ArrayList<String>(orphanNotes));
            this.lostComments = lostComments;
            this.modifiedOutside = modifiedOutside;
        }

        /** 没有校验错误。外部改动不算错误，由调用方决定是否二次确认。 */
        public boolean ok() {
            return violations.isEmpty();
        }

        /**
         * 是否有注释将在保存后消失。提示条数非空即为真；调用方在写入前必须让用户确认。
         */
        public boolean losesComments() {
            return !orphanNotes.isEmpty();
        }
    }

    public static Result prepare(EditorModel model, Path yamlFile) throws IOException {
        return prepare(model, yamlFile, null);
    }

    /**
     * 读当前文件、扫描注释、渲染并合并注释，算好 diff。
     *
     * <p>{@code baseline} 非空时会与文件当前指纹比较；不一致就把 {@code modifiedOutside}
     * 置为真。这个标记不阻塞保存，只让 UI 有机会问一句。</p>
     */
    public static Result prepare(EditorModel model, Path yamlFile, FileStamp baseline)
            throws IOException {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        String oldText = read(yamlFile);
        boolean modifiedOutside = baseline != null && !baseline.matches(FileStamp.of(yamlFile));

        try {
            List<Violation> violations = model.validate();
            if (!violations.isEmpty()) {
                return new Result(violations, "", Collections.<String>emptyList(),
                        Collections.<String>emptyList(), 0, modifiedOutside);
            }

            String rendered;
            try {
                rendered = SpecWriter.render(model.toSpec());
            } catch (IllegalArgumentException e) {
                Violation violation = new Violation("", messageOf(e));
                return new Result(Collections.singletonList(violation), "",
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0,
                        modifiedOutside);
            }

            Comments source = scanSafely(oldText);
            Comments template = templateSafely(model.name());
            YamlCommentScanner.ApplyResult applied =
                    YamlCommentScanner.apply(rendered, source, template);

            return new Result(Collections.<Violation>emptyList(), applied.text,
                    LineDiff.diff(oldText, applied.text),
                    notes(applied, source),
                    Math.max(applied.dropped, source.orphans.size()),
                    modifiedOutside);
        } catch (RuntimeException e) {
            // 扫描/合并/渲染的意外不该把 TUI 带崩；把它降级成一条校验错误留在屏上。
            Violation violation = new Violation("", messageOf(e));
            return new Result(Collections.singletonList(violation), "",
                    Collections.<String>emptyList(), Collections.<String>emptyList(), 0,
                    modifiedOutside);
        }
    }

    /** 原子写入规范文本（UTF-8、0644、同目录临时文件 + ATOMIC_MOVE）。 */
    public static void commit(Path yamlFile, String text) throws IOException {
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        SpecWriter.save(yamlFile, text);
    }

    /**
     * 原子写入，并在落盘前再验一次指纹：prepare 到 commit 之间文件又被改过就拒绝写入，
     * 让用户带着最新内容重新进编辑器，而不是覆盖别人的修改。
     */
    public static void commit(Path yamlFile, String text, FileStamp baseline) throws IOException {
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (baseline != null) {
            FileStamp now = FileStamp.of(yamlFile);
            if (!baseline.matches(now)) {
                throw new IOException("file changed outside: " + yamlFile);
            }
        }
        SpecWriter.save(yamlFile, text);
    }

    private static List<String> notes(YamlCommentScanner.ApplyResult applied, Comments source) {
        int orphans = source.orphans.size();
        int unplaced = Math.max(0, applied.dropped - orphans);
        List<String> notes = new ArrayList<String>(2);
        if (unplaced > 0) {
            notes.add(unplaced + " comments could not be placed and will be dropped");
        }
        if (orphans > 0) {
            notes.add(orphans + " commented-out field examples will not be kept");
        }
        return notes;
    }

    private static Comments scanSafely(String text) {
        try {
            return YamlCommentScanner.scan(text);
        } catch (RuntimeException e) {
            return Comments.empty();
        }
    }

    private static Comments templateSafely(String name) {
        try {
            return YamlCommentScanner.scanTemplate(name);
        } catch (RuntimeException e) {
            return Comments.empty();
        }
    }

    private static String read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        if (Files.isDirectory(file)) {
            throw new IOException(file + " is a directory");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
