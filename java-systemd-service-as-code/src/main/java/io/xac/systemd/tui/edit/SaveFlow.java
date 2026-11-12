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
 * 部署前的检查留给 apply。外部改动只做提醒，不阻止覆盖：调用方拿到 {@code modifiedOutside}
 * 后可以先提示一次，用户确认后再保存。</p>
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
        public final boolean modifiedOutside;

        Result(List<Violation> violations, String newText, List<String> diff,
               List<String> orphanNotes, boolean modifiedOutside) {
            this.violations = Collections.unmodifiableList(new ArrayList<Violation>(violations));
            this.newText = newText;
            this.diff = Collections.unmodifiableList(new ArrayList<String>(diff));
            this.orphanNotes = Collections.unmodifiableList(new ArrayList<String>(orphanNotes));
            this.modifiedOutside = modifiedOutside;
        }

        /** 没有校验错误。外部改动不算错误，由调用方决定是否二次确认。 */
        public boolean ok() {
            return violations.isEmpty();
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

        List<Violation> violations = model.validate();
        if (!violations.isEmpty()) {
            return new Result(violations, "", Collections.<String>emptyList(),
                    Collections.<String>emptyList(), modifiedOutside);
        }

        String rendered;
        try {
            rendered = SpecWriter.render(model.toSpec());
        } catch (IllegalArgumentException e) {
            Violation violation = new Violation("", messageOf(e));
            return new Result(Collections.singletonList(violation), "",
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    modifiedOutside);
        }

        Comments source;
        try {
            source = YamlCommentScanner.scan(oldText);
        } catch (RuntimeException e) {
            // 扫描器只做文本分析，但损坏的输入不该把整个编辑器带崩。
            source = Comments.empty();
        }
        Comments template;
        try {
            template = YamlCommentScanner.scanTemplate(model.name());
        } catch (RuntimeException e) {
            template = Comments.empty();
        }
        YamlCommentScanner.ApplyResult applied =
                YamlCommentScanner.apply(rendered, source, template);

        List<String> notes = new ArrayList<String>(2);
        if (applied.dropped > 0) {
            notes.add(applied.dropped + " comments could not be placed");
        }
        if (!source.orphans.isEmpty()) {
            notes.add(source.orphans.size() + " comments left as-is (commented-out fields)");
        }
        return new Result(Collections.<Violation>emptyList(), applied.text,
                LineDiff.diff(oldText, applied.text), notes, modifiedOutside);
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

    private static String read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
