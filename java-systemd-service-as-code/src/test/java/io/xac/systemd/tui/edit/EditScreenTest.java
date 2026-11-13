package io.xac.systemd.tui.edit;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecWriter;
import io.xac.systemd.tui.NavKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑屏状态机：焦点移动、标量/行编辑、保存校验与外部改动二次确认、撤销、脏退出。
 */
class EditScreenTest {

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

    private EditorModel model() {
        return EditorModel.from(fullSpec(), Comments.empty());
    }

    private Path write() throws IOException {
        Path file = pkg.resolve("xac.yaml");
        Files.write(file, SpecWriter.render(fullSpec()).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void press(EditScreen screen, NavKey key) {
        screen.handle(key, '\0');
    }

    private static void type(EditScreen screen, String text) {
        for (int i = 0; i < text.length(); i++) {
            screen.handle(NavKey.CHAR, text.charAt(i));
        }
    }

    private static boolean anyContains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyStartsWith(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void rendersAllFieldsWithHintsAndFocusOnFirstEditable() throws Exception {
        EditScreen screen = new EditScreen(model(), write());

        List<String> lines = screen.lines(120, 0);

        assertEquals(17, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("  name"), lines.get(0));
        assertTrue(lines.get(0).contains("(read-only)"), lines.get(0));
        assertTrue(lines.get(1).startsWith("> description"), lines.get(1));
        assertTrue(lines.get(1).contains("demo service"), lines.get(1));
        assertTrue(anyContains(lines, EditorView.HINT_EDIT), lines.toString());
        assertTrue(anyContains(lines, EditorView.HINT_KEYS), lines.toString());
        assertEquals("mode: browse  field: description", lines.get(16));
    }

    @Test
    void tabJumpsSegmentsAndArrowsSkipReadOnlyName() throws Exception {
        EditScreen screen = new EditScreen(model(), write());

        assertEquals(1, screen.focus());
        press(screen, NavKey.TAB);
        assertEquals(2, screen.focus(), "artifact.jar");
        press(screen, NavKey.TAB);
        assertEquals(3, screen.focus(), "runtime.java");
        press(screen, NavKey.SHIFT_TAB);
        assertEquals(2, screen.focus());

        press(screen, NavKey.UP);
        assertEquals(1, screen.focus());
        press(screen, NavKey.UP);
        assertEquals(1, screen.focus(), "read-only name must be skipped");

        press(screen, NavKey.DOWN);
        assertEquals(2, screen.focus());
        press(screen, NavKey.TAB);
        assertEquals(3, screen.focus());
        press(screen, NavKey.TAB);
        assertEquals(5, screen.focus(), "env starts a new segment");
        press(screen, NavKey.SHIFT_TAB);
        assertEquals(3, screen.focus());
    }

    @Test
    void enterEditsScalarAndCommitsToModel() throws Exception {
        EditScreen screen = new EditScreen(model(), write());

        press(screen, NavKey.ENTER);
        assertEquals(EditScreen.Mode.EDIT_SCALAR, screen.mode());
        assertTrue(anyContains(screen.lines(120, 0), "> demo service_"));
        type(screen, " plus");
        press(screen, NavKey.ENTER);

        assertEquals(EditScreen.Mode.BROWSE, screen.mode());
        assertEquals("demo service plus",
                screen.model().field("description").values.get(0));
        assertTrue(screen.dirty());
    }

    @Test
    void spaceCyclesBooleanAndCClearsScalar() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        for (int i = 0; i < 9; i++) {
            press(screen, NavKey.DOWN);
        }
        assertEquals("lifecycle.auto-start", currentPath(screen));

        screen.handle(NavKey.CHAR, ' ');
        assertEquals("false", value(screen, "lifecycle.auto-start"));
        screen.handle(NavKey.CHAR, ' ');
        assertEquals("", value(screen, "lifecycle.auto-start"));
        screen.handle(NavKey.CHAR, ' ');
        assertEquals("true", value(screen, "lifecycle.auto-start"));

        screen.handle(NavKey.CHAR, 'c');
        assertEquals("", value(screen, "lifecycle.auto-start"));
    }

    @Test
    void rightEntersRowEditorAndAAddsRow() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        for (int i = 0; i < 3; i++) {
            press(screen, NavKey.DOWN);
        }
        assertEquals("runtime.opts", currentPath(screen));

        press(screen, NavKey.RIGHT);
        assertEquals(EditScreen.Mode.EDIT_LINES, screen.mode());
        assertTrue(anyContains(screen.lines(120, 0), "rows: runtime.opts  (1 row)"));

        screen.handle(NavKey.CHAR, 'a');
        assertEquals(2, screen.model().field("runtime.opts").values.size());
        assertTrue(anyContains(screen.lines(120, 0), "> 2"), screen.lines(120, 0).toString());

        press(screen, NavKey.ENTER);
        assertEquals(EditScreen.Mode.EDIT_SCALAR, screen.mode());
        type(screen, "-Dfoo=bar");
        press(screen, NavKey.ENTER);
        assertEquals(EditScreen.Mode.EDIT_LINES, screen.mode());
        assertEquals("-Dfoo=bar", screen.model().field("runtime.opts").values.get(1));

        screen.handle(NavKey.CHAR, 'x');
        assertEquals(1, screen.model().field("runtime.opts").values.size());
    }

    @Test
    void saveWithValidationErrorsStaysAndListsViolations() throws Exception {
        Path file = write();
        byte[] before = Files.readAllBytes(file);
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.TAB);
        assertEquals("artifact.jar", currentPath(screen));
        screen.handle(NavKey.CHAR, 'c');

        screen.handle(NavKey.CHAR, 's');

        assertFalse(screen.closed());
        assertFalse(screen.saved());
        assertEquals(2, screen.focus(), "cursor jumps to the first bad field");
        List<String> lines = screen.lines(120, 0);
        assertTrue(anyContains(lines, "! artifact.jar: must not be empty"), lines.toString());
        assertArrayEqualsQuiet(before, Files.readAllBytes(file));
    }

    @Test
    void saveErrorListStopsAtThreeLines() throws Exception {
        EditScreen screen = new EditScreen(model()
                .with("artifact.jar", list(""))
                .with("runtime.java", list(""))
                .with("runtime.opts", list(""))
                .with("lifecycle.restart-sec", list("0")), write());

        screen.handle(NavKey.CHAR, 's');

        List<String> lines = screen.lines(120, 0);
        int shown = 0;
        for (String line : lines) {
            if (line.startsWith("! ")) {
                shown++;
            }
        }
        assertEquals(3, shown, lines.toString());
        assertTrue(anyContains(lines, "... (1 more)"), lines.toString());
    }

    @Test
    void saveOpensPreviewAndWritesOnlyAfterY() throws Exception {
        Path file = write();
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);
        byte[] before = Files.readAllBytes(file);

        screen.handle(NavKey.CHAR, 's');

        assertFalse(screen.closed());
        assertArrayEqualsQuiet(before, Files.readAllBytes(file));
        List<String> lines = screen.lines(120, 0);
        assertTrue(lines.get(0).startsWith("save preview  (+"), lines.toString());
        assertTrue(anyContains(lines, "+ description: demo service plus"), lines.toString());

        screen.handle(NavKey.CHAR, 'y');

        assertTrue(screen.closed());
        assertTrue(screen.saved());
        assertFalse(screen.dirty());
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains("description: demo service plus"), text);
        assertTrue(text.endsWith("\n"), text);
    }

    @Test
    void previewWarnsWhenTheFileChangedOutsideAndDiffShowsIt() throws Exception {
        Path file = write();
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);
        Files.write(file, "\nworkdir: /tmp/external\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        screen.handle(NavKey.CHAR, 's');

        List<String> lines = screen.lines(120, 0);
        assertTrue(anyContains(lines, "! file changed outside since this screen opened"),
                lines.toString());
        assertTrue(anyContains(lines, "workdir: /tmp/external"), lines.toString());

        screen.handle(NavKey.CHAR, 'y');

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(text.contains("/tmp/external"), text);
        assertTrue(text.contains("description: demo service plus"), text);
    }

    @Test
    void previewCountsCommentsThatWillBeDroppedAndYCommitsOnce() throws Exception {
        Path file = write();
        Files.write(file, "\n# stray tail note\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(file);
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);

        screen.handle(NavKey.CHAR, 's');

        assertFalse(screen.closed());
        assertArrayEqualsQuiet(before, Files.readAllBytes(file));
        List<String> lines = screen.lines(120, 0);
        assertTrue(anyContains(lines, "! 1 comment(s) will be dropped"), lines.toString());
        assertTrue(screen.saveNote().isEmpty(), screen.saveNote().toString());

        screen.handle(NavKey.CHAR, 'y');

        assertTrue(screen.closed());
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(text.contains("stray tail note"), text);
        assertTrue(text.contains("description: demo service plus"), text);
        assertTrue(screen.saveNote().contains("1 comment(s) were dropped"),
                screen.saveNote().toString());
    }

    @Test
    void escapeLeavesPreviewAndKeepsEdits() throws Exception {
        Path file = write();
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);
        byte[] before = Files.readAllBytes(file);

        screen.handle(NavKey.CHAR, 's');
        press(screen, NavKey.ESC);

        assertFalse(screen.closed());
        assertEquals(EditScreen.Mode.BROWSE, screen.mode());
        assertArrayEqualsQuiet(before, Files.readAllBytes(file));
        assertEquals("demo service plus", value(screen, "description"));
    }

    @Test
    void arrowsScrollThePreviewInsteadOfMovingFocus() throws Exception {
        Path file = write();
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);

        screen.handle(NavKey.CHAR, 's');
        press(screen, NavKey.DOWN);
        press(screen, NavKey.PGDN);

        assertEquals(EditScreen.Mode.CONFIRM_SAVE, screen.mode());
        screen.handle(NavKey.CHAR, 'y');
        assertTrue(screen.closed());
    }

    @Test
    void externalChangeAfterPreviewBlocksTheWrite() throws Exception {
        Path file = write();
        EditScreen screen = new EditScreen(model(), file);
        press(screen, NavKey.ENTER);
        type(screen, " plus");
        press(screen, NavKey.ENTER);
        screen.handle(NavKey.CHAR, 's');

        Files.write(file, "\n# late edit\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        byte[] afterLate = Files.readAllBytes(file);
        screen.handle(NavKey.CHAR, 'y');

        assertFalse(screen.closed());
        assertTrue(anyContains(screen.lines(120, 0),
                "cannot save: file changed outside"), screen.lines(120, 0).toString());
        assertArrayEqualsQuiet(afterLate, Files.readAllBytes(file));

        screen.handle(NavKey.CHAR, 's');
        assertTrue(anyContains(screen.lines(120, 0), "! file changed outside"), screen.lines(120, 0).toString());
        screen.handle(NavKey.CHAR, 'y');
        assertTrue(screen.closed());
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(text.contains("late edit"), text);
        assertTrue(text.contains("description: demo service plus"), text);
    }
    @Test
    void unchangedBufferDoesNotMarkScreenDirty() throws Exception {
        EditScreen screen = new EditScreen(model(), write());

        press(screen, NavKey.ENTER);
        press(screen, NavKey.ENTER);

        assertFalse(screen.dirty());
        assertFalse(screen.closed());
    }

    @Test
    void undoRestoresPreviousTextAcrossTwoEdits() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        type(screen, "x");
        press(screen, NavKey.ENTER);
        press(screen, NavKey.ENTER);
        type(screen, "y");
        press(screen, NavKey.ENTER);
        assertEquals("demo servicexy", value(screen, "description"));

        screen.handle(NavKey.CHAR, 'u');
        assertEquals("demo servicex", value(screen, "description"));
        assertTrue(screen.dirty());
        screen.handle(NavKey.CHAR, 'u');
        assertEquals("demo service", value(screen, "description"));
        assertFalse(screen.dirty());
        screen.handle(NavKey.CHAR, 'u');
        assertTrue(anyContains(screen.lines(120, 0), "nothing to undo"));
    }

    @Test
    void quitOnDirtyScreenNeedsConfirmation() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        type(screen, "!");
        press(screen, NavKey.ENTER);

        screen.handle(NavKey.CHAR, 'q');
        assertFalse(screen.closed());
        assertTrue(anyContains(screen.lines(120, 0), "discard unsaved changes?"));

        screen.handle(NavKey.CHAR, 'n');
        assertFalse(screen.closed());
        assertTrue(screen.dirty());

        screen.handle(NavKey.CHAR, 'q');
        screen.handle(NavKey.CHAR, 'y');
        assertTrue(screen.closed());
        assertFalse(screen.saved());
    }

    @Test
    void showsFieldHelpFromScannedComments() throws Exception {
        Map<String, String> inline = Collections.emptyMap();
        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        leading.put("description", list("my note"));
        Comments comments = new Comments(inline, leading, Collections.<String>emptyList());
        EditScreen screen = new EditScreen(
                EditorModel.from(fullSpec(), comments), write());

        assertTrue(anyContains(screen.lines(120, 0), "# my note"));
    }

    // ------------------------------------------------- nested validation paths

    @Test
    void focusesOptsRowForIndexedValidationPath() throws Exception {
        EditScreen screen = new EditScreen(
                model().with("runtime.opts", list("${MISSING}")), write());

        List<String> lines = screen.lines(120, 0);

        assertEquals("runtime.opts", currentPath(screen));
        assertTrue(lines.get(4).contains("! "), lines.get(4));
    }

    @Test
    void focusesEnvRowForKeyedValidationPath() throws Exception {
        EditScreen screen = new EditScreen(
                model().with("env", list("1BAD=x")), write());

        List<String> lines = screen.lines(120, 0);

        assertEquals("env", currentPath(screen));
        assertTrue(lines.get(5).contains("! "), lines.get(5));
    }

    @Test
    void focusesPathLinksRowForIndexedValidationPath() throws Exception {
        EditScreen screen = new EditScreen(
                model().with("path-links", list("./conf:/var")), write());

        List<String> lines = screen.lines(120, 0);

        assertEquals("path-links", currentPath(screen));
        assertTrue(lines.get(8).contains("! "), lines.get(8));
    }

    @Test
    void focusesExactFieldWhenValidationPathHasNoSuffix() throws Exception {
        EditScreen screen = new EditScreen(
                model().with("runtime.java", list("")), write());

        List<String> lines = screen.lines(120, 0);

        assertEquals("runtime.java", currentPath(screen));
        assertTrue(lines.get(3).contains("! "), lines.get(3));
    }

    // ------------------------------------------------------------ cursor edit

    @Test
    void arrowMovesInsertPointAndTypingInsertsThere() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        press(screen, NavKey.LEFT);
        press(screen, NavKey.LEFT);
        type(screen, "XY");
        press(screen, NavKey.ENTER);

        assertEquals("demo serviXYce", value(screen, "description"));
    }

    @Test
    void homeAndEndJumpToBufferEnds() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        press(screen, NavKey.TOP);
        type(screen, "X");
        press(screen, NavKey.BOTTOM);
        type(screen, "Y");
        press(screen, NavKey.ENTER);

        assertEquals("Xdemo serviceY", value(screen, "description"));
    }

    @Test
    void deleteRemovesCharacterUnderCursor() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        press(screen, NavKey.TOP);
        press(screen, NavKey.DELETE);
        press(screen, NavKey.ENTER);

        assertEquals("emo service", value(screen, "description"));
    }

    @Test
    void backspaceInTheMiddleJoinsBothSides() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        press(screen, NavKey.LEFT);
        press(screen, NavKey.LEFT);
        press(screen, NavKey.LEFT);
        press(screen, NavKey.BACKSPACE);
        press(screen, NavKey.ENTER);

        assertEquals("demo serice", value(screen, "description"));
    }

    @Test
    void rendersCursorAtInsertPointInsteadOfAlwaysAtEnd() throws Exception {
        EditScreen screen = new EditScreen(model(), write());
        press(screen, NavKey.ENTER);
        press(screen, NavKey.TOP);
        type(screen, "Z");

        List<String> lines = screen.lines(120, 0);

        assertTrue(anyContains(lines, "Z_demo service"),
                String.valueOf(lines));
    }

    @Test
    void arrowLeftSkipsAWholeCodePoint() throws Exception {
        EditScreen screen = new EditScreen(
                model().with("description", list("")), write());
        press(screen, NavKey.ENTER);
        type(screen, "\uD83D\uDE00");
        press(screen, NavKey.LEFT);
        type(screen, "A");
        press(screen, NavKey.ENTER);

        assertEquals("A\uD83D\uDE00", value(screen, "description"));
    }

    // --------------------------------------------------------------- details

    private static String currentPath(EditScreen screen) {
        for (String line : screen.lines(120, 0)) {
            if (line.startsWith("mode: ")) {
                int index = line.indexOf("field: ");
                return line.substring(index + "field: ".length());
            }
        }
        return "";
    }

    private static String value(EditScreen screen, String path) {
        List<String> values = screen.model().field(path).values;
        return values.isEmpty() ? "" : values.get(0);
    }

    private static void assertArrayEqualsQuiet(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }
}
