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
    void reportsOrphanNotesWithCounts() throws IOException {
        Path file = write(SpecWriter.render(fullSpec()) + "\n# stray tail note\n");

        SaveFlow.Result result = SaveFlow.prepare(model(), file);

        assertTrue(result.ok(), String.valueOf(result.violations));
        assertTrue(result.orphanNotes.contains("1 comments could not be placed"),
                result.orphanNotes.toString());
        assertTrue(result.orphanNotes.contains(
                        "1 comments left as-is (commented-out fields)"),
                result.orphanNotes.toString());
        assertFalse(result.newText.contains("stray tail note"), result.newText);
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
        assertTrue(result.orphanNotes.contains("1 comments left as-is (commented-out fields)"),
                result.orphanNotes.toString());
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
        EditorModel changed = model().with("description", list("changed"));
        SaveFlow.Result result = SaveFlow.prepare(changed, file);

        SaveFlow.commit(file, result.newText);

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

        SaveFlow.commit(file, "name: demo\n");

        assertEquals("name: demo\n", new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8));
    }
}
