package io.xac.systemd.tui.edit;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecWriter;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保存流程契约：注释回挂、外部改动指纹、校验短路、原子落盘。
 */
class SaveFlowTest {

    @TempDir
    Path pkg;

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> map(String... pairs) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static List<String> list(String... items) {
        return new ArrayList<String>(Arrays.asList(items));
    }

    private ServiceSpec fullSpec() {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(list("-Xmx256m"))
                .env(map("GREETING", "hello"))
                .lifecycle(new Lifecycle(Boolean.TRUE, "on-failure", 5, 60))
                .build();
    }

    private Path write(String text) throws IOException {
        Path file = pkg.resolve("xac.yaml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private EditorModel model() {
        return EditorModel.from(fullSpec(), Comments.empty());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void keepsLeadingCommentWhenFieldChanges() throws IOException {
        String text = SpecWriter.render(fullSpec())
                .replace("description: demo service", "# my note\ndescription: demo service");
        Path file = write(text);
        EditorModel changed = model().with("description", list("changed"));

        SaveFlow.Result result = SaveFlow.prepare(changed, file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.newText.contains("# my note"), result.newText);
        assertTrue(result.newText.contains("description: changed"), result.newText);
        assertTrue(result.diff.contains("- description: demo service"), result.diff.toString());
        assertTrue(result.diff.contains("+ description: changed"), result.diff.toString());
        assertFalse(result.modifiedOutside);
    }

    @Test
    void keepsInlineCommentOnChangedField() throws IOException {
        String text = SpecWriter.render(fullSpec())
                .replace("description: demo service", "description: demo service  # my note");
        Path file = write(text);
        EditorModel changed = model().with("description", list("changed"));

        SaveFlow.Result result = SaveFlow.prepare(changed, file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.newText.contains("description: changed  # my note"), result.newText);
    }

    @Test
    void reportsProseCommentLossCount() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()) + "\n# stray tail note\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.orphanNotes.isEmpty(),
                "no example lines: the ! line already carries the count");
        assertEquals(1, result.lostComments, result.orphanNotes.toString());
        assertEquals(0, result.exampleComments, result.orphanNotes.toString());
        assertTrue(result.losesComments());
        assertFalse(result.newText.contains("stray tail note"), result.newText);
    }

    @Test
    void reportsCommentsThatCannotBePlacedAtAll() throws IOException {
        String text = SpecWriter.render(fullSpec()) + "\n# gone comment\nworkdir: /tmp/old\n";
        Path file = write(text);

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.orphanNotes.isEmpty(), result.orphanNotes.toString());
        assertEquals(1, result.lostComments, result.orphanNotes.toString());
        assertEquals(0, result.exampleComments, result.orphanNotes.toString());
        assertFalse(result.newText.contains("gone comment"), result.newText);
    }

    @Test
    void classifiesMixedProseAndExampleCommentsSeparately() throws IOException {
        // 散文 + 被注释掉的字段示例：都丢失，但只有示例计入 exampleComments，
        // 提示行只补充分类，不重复总数。
        Path file = write(SpecWriter.render(fullSpec())
                + "\n# prose note\n# workdir: /opt/prod/work\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertEquals(2, result.lostComments, result.orphanNotes.toString());
        assertEquals(1, result.exampleComments, result.orphanNotes.toString());
        assertEquals(1, result.orphanNotes.size(), result.orphanNotes.toString());
        assertTrue(result.orphanNotes.get(0).contains("1 commented-out example"),
                result.orphanNotes.toString());
    }

    @Test
    void countsOnlyCommentedOutFieldExamplesWhenAllLostLinesAreExamples() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()) + "\n# workdir: /opt/prod/work\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertEquals(1, result.lostComments, result.orphanNotes.toString());
        assertEquals(1, result.exampleComments, result.orphanNotes.toString());
        assertEquals(1, result.orphanNotes.size(), result.orphanNotes.toString());
        assertTrue(result.orphanNotes.get(0).contains("1 commented-out example"),
                result.orphanNotes.toString());
    }

    @Test
    void freeTextThatLooksLikeAKeyIsCountedAsProse() throws IOException {
        // 只有 DSL 键名才算「被注释掉的字段示例」；# Note: ... 是散文。
        Path file = write(SpecWriter.render(fullSpec())
                + "\n# Note: this is prose\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertEquals(1, result.lostComments, result.orphanNotes.toString());
        assertEquals(0, result.exampleComments, result.orphanNotes.toString());
        assertTrue(result.orphanNotes.isEmpty(), result.orphanNotes.toString());
    }

    @Test
    void templateVerbatimOrphanIsNotCountedAsLost() throws IOException {
        // 与模板逐字相同的注释即使成了 orphan，模板打底也会重新写出来，不算丢失。
        Path file = write(SpecWriter.render(fullSpec())
                + "\n# The schema is strict: unknown keys are errors.\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertEquals(0, result.lostComments, result.orphanNotes.toString());
        assertEquals(0, result.exampleComments, result.orphanNotes.toString());
        assertFalse(result.losesComments());
    }

    @Test
    void scannerFailureBecomesAViolationInsteadOfThrowing() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));

        SaveFlow.Result result = SaveFlow.prepare(model(), file, null,
                new SaveFlow.CommentScanner() {
                    @Override
                    public Comments scan(String text) {
                        throw new IllegalStateException("scanner exploded");
                    }
                });

        assertFalse(result.ok());
        assertEquals(1, result.violations.size(), result.violations.toString());
        assertTrue(result.violations.get(0).toString().contains("scanner exploded"),
                result.violations.toString());
    }

    @Test
    void detectsExternalModificationAgainstBaseline() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        FileStamp baseline = FileStamp.of(file);
        Files.write(file, "# external\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        SaveFlow.Result result = SaveFlow.prepare(model(), file, baseline);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.modifiedOutside);
        assertEquals(1, result.lostComments, result.orphanNotes.toString());
        assertEquals(0, result.exampleComments, result.orphanNotes.toString());
    }

    @Test
    void detectsSameSizeRewriteWithRestoredMtime() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        FileStamp baseline = FileStamp.of(file);
        java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(file);
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // 同长度改写，再把 mtime 原样塞回去：只看 mtime+size 的实现会漏掉。
        Files.write(file, text.replace("demo service", "demo servicX")
                .getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, mtime);

        assertEquals(baseline.size(), FileStamp.of(file).size());
        assertEquals(baseline.mtimeMillis(), FileStamp.of(file).mtimeMillis());
        SaveFlow.Result result = SaveFlow.prepare(model(), file, baseline);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.modifiedOutside);
    }

    @Test
    void commitRejectsBaselineThatWentStale() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        FileStamp baseline = FileStamp.of(file);
        EditorModel changed = model().with("description", list("changed"));
        SaveFlow.Result result = SaveFlow.prepare(changed, file, baseline);
        byte[] before = Files.readAllBytes(file);
        Files.write(file, "# sneaky\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        IOException failure = assertThrows(IOException.class,
                () -> SaveFlow.commit(file, result.newText, baseline));

        assertTrue(failure.getMessage().startsWith("file changed outside: "),
                failure.getMessage());
        byte[] after = Files.readAllBytes(file);
        assertEquals(new String(before, StandardCharsets.UTF_8) + "# sneaky\n",
                new String(after, StandardCharsets.UTF_8));
    }

    @Test
    void matchingBaselineIsNotReportedAsModified() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        FileStamp baseline = FileStamp.of(file);

        SaveFlow.Result result = SaveFlow.prepare(model(), file, baseline);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertFalse(result.modifiedOutside);
    }

    @Test
    void validationFailuresShortCircuitRendering() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        EditorModel invalid = model().with("artifact.jar", list(""));

        SaveFlow.Result result = SaveFlow.prepare(invalid, file);

        assertFalse(result.ok());
        assertEquals(1, result.violations.size(), result.violations.toString());
        Violation violation = result.violations.get(0);
        assertEquals("artifact.jar", violation.path);
        assertEquals("must not be empty", violation.message);
        assertEquals("", result.newText);
        assertTrue(result.diff.isEmpty());
    }

    @Test
    void commitWritesExactBytesAndLeavesNoTempFile() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()));
        FileStamp baseline = FileStamp.of(file);
        EditorModel changed = model().with("description", list("changed"));
        SaveFlow.Result result = SaveFlow.prepare(changed, file, baseline);

        SaveFlow.commit(file, result.newText, baseline);

        assertArrayEquals(result.newText.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(file));
        DirectoryStream<Path> temps = Files.newDirectoryStream(pkg, ".xac-*.tmp");
        try {
            assertFalse(temps.iterator().hasNext(), "temp file must be gone");
        } finally {
            temps.close();
        }
    }

    @Test
    void commitCreatesMissingFile() throws IOException {
        Path file = pkg.resolve("new.yaml");

        SaveFlow.commit(file, "name: demo\n", FileStamp.of(file));

        assertEquals("name: demo\n", new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8));
    }

    @Test
    void directoryTargetIsReportedClearly() throws IOException {
        Path dir = pkg.resolve("xac.yaml");
        Files.createDirectory(dir);

        IOException failure = assertThrows(IOException.class,
                () -> SaveFlow.prepare(model(), dir));

        assertTrue(failure.getMessage().contains("is a directory"),
                failure.getMessage());
    }
}
