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
        public final int exampleComments;
        public final boolean modifiedOutside;

        Result(List<Violation> violations, String newText, List<String> diff,
               List<String> orphanNotes, int lostComments, int exampleComments,
               boolean modifiedOutside) {
            this.violations = Collections.unmodifiableList(new ArrayList<Violation>(violations));
            this.newText = newText;
            this.diff = Collections.unmodifiableList(new ArrayList<String>(diff));
            this.orphanNotes = Collections.unmodifiableList(new ArrayList<String>(orphanNotes));
            this.lostComments = lostComments;
            this.exampleComments = exampleComments;
            this.modifiedOutside = modifiedOutside;
        }

        /** 没有校验错误。外部改动不算错误，由调用方决定是否二次确认。 */
        public boolean ok() {
            return violations.isEmpty();
        }

        /**
         * 是否有注释将在保存后消失。{@code lostComments} 为正即为真；调用方在写入前必须让用户确认。
         */
        public boolean losesComments() {
            return lostComments > 0;
        }
    }

    /**
     * 注释扫描的包内接缝，方便测试注入会抛异常的扫描器，验证保存流程不会因此崩掉。
     */
    interface CommentScanner {
        Comments scan(String text);
    }

    private static final CommentScanner DEFAULT_SCANNER = new CommentScanner() {
        @Override
        public Comments scan(String text) {
            return YamlCommentScanner.scan(text);
        }
    };

    public static Result prepare(EditorModel model, Path yamlFile) throws IOException {
        return prepare(model, yamlFile, null, null);
    }

    /**
     * 读当前文件、扫描注释、渲染并合并注释，算好 diff。
     *
     * <p>{@code baseline} 非空时会与文件当前指纹比较；不一致就把 {@code modifiedOutside}
     * 置为真。这个标记不阻塞保存，只让 UI 有机会问一句。</p>
     */
    public static Result prepare(EditorModel model, Path yamlFile, FileStamp baseline)
            throws IOException {
        return prepare(model, yamlFile, baseline, null);
    }

    static Result prepare(EditorModel model, Path yamlFile, FileStamp baseline,
                          CommentScanner scanner) throws IOException {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        CommentScanner effective = scanner == null ? DEFAULT_SCANNER : scanner;
        String oldText = read(yamlFile);
        boolean modifiedOutside = baseline != null && !baseline.matches(FileStamp.of(yamlFile));

        try {
            List<Violation> violations = model.validate();
            if (!violations.isEmpty()) {
                return new Result(violations, "", Collections.<String>emptyList(),
                        Collections.<String>emptyList(), 0, 0, modifiedOutside);
            }

            String rendered;
            try {
                rendered = SpecWriter.render(model.toSpec());
            } catch (IllegalArgumentException e) {
                Violation violation = new Violation("", messageOf(e));
                return new Result(Collections.singletonList(violation), "",
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0, 0,
                        modifiedOutside);
            }

            Comments source = effective.scan(oldText);
            Comments template = YamlCommentScanner.scanTemplate(model.name());
            YamlCommentScanner.ApplyResult applied =
                    YamlCommentScanner.apply(rendered, source, template);

            int lost = applied.dropped + applied.examplesDropped;
            return new Result(Collections.<Violation>emptyList(), applied.text,
                    LineDiff.diff(oldText, applied.text),
                    notes(applied),
                    lost,
                    applied.examplesDropped,
                    modifiedOutside);
        } catch (RuntimeException e) {
            // 扫描/合并/渲染的意外不该把 TUI 带崩；把它降级成一条校验错误留在屏上。
            Violation violation = new Violation("", messageOf(e));
            return new Result(Collections.singletonList(violation), "",
                    Collections.<String>emptyList(), Collections.<String>emptyList(), 0, 0,
                    modifiedOutside);
        }
    }

    /**
     * 原子写入，并在落盘前再验一次指纹：prepare 到 commit 之间文件又被改过就拒绝写入，
     * 让用户带着最新内容重新进编辑器，而不是覆盖别人的修改。{@code baseline} 必须非
     * null（null 抛 {@link IllegalArgumentException}），没有「跳过复验」的公开入口。
     */
    public static void commit(Path yamlFile, String text, FileStamp baseline) throws IOException {
        if (yamlFile == null) {
            throw new IllegalArgumentException("yamlFile must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (baseline == null) {
            throw new IllegalArgumentException("baseline is required");
        }
        FileStamp now = FileStamp.of(yamlFile);
        if (!baseline.matches(now)) {
            throw new IOException("file changed outside: " + yamlFile);
        }
        SpecWriter.save(yamlFile, text);
    }

    /**
     * 丢失提示的分类行。条数由 {@code lostComments} 单独给出，这里只补充「其中多少是被注释掉的
     * 字段示例」，避免同一行重复同一个数字。
     */
    private static List<String> notes(YamlCommentScanner.ApplyResult applied) {
        List<String> notes = new ArrayList<String>(1);
        int examples = applied.examplesDropped;
        if (examples <= 0) {
            return notes;
        }
        notes.add(examples + " commented-out example" + (examples == 1 ? "" : "s"));
        return notes;
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
