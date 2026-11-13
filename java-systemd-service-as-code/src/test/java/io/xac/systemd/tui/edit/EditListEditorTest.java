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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rows editor: two columns (key/value or value), per-row comments, key editing,
 * and row reordering.
 */
class EditListEditorTest {

    @TempDir
    Path pkg;

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

    private ServiceSpec spec(Map<String, String> env, List<String> links) {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .description("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(list("-Xmx256m"))
                .env(env)
                .pathLinks(links)
                .lifecycle(new Lifecycle(Boolean.TRUE, "on-failure", 5, 60))
                .build();
    }

    private Path write() throws IOException {
        Path file = pkg.resolve("xac.yaml");
        Files.write(file, SpecWriter.render(spec(map("A", "1"), list())).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private EditScreen open(Map<String, String> env, List<String> links, Comments comments)
            throws IOException {
        return new EditScreen(EditorModel.from(spec(env, links), comments), write());
    }

    private static void press(EditScreen screen, NavKey key) {
        screen.handle(key, '\0');
    }

    private static void type(EditScreen screen, String text) {
        for (int i = 0; i < text.length(); i++) {
            screen.handle(NavKey.CHAR, text.charAt(i));
        }
    }

    private static void tabTo(EditScreen screen, int times) {
        for (int i = 0; i < times; i++) {
            press(screen, NavKey.TAB);
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

    private static List<String> values(EditScreen screen, String path) {
        return screen.model().field(path).values;
    }

    @Test
    void savePreviewCollapsesLongUnchangedRuns() throws Exception {
        EditScreen screen = open(
                map("A", "1", "B", "2", "C", "3", "D", "4", "E", "5"),
                list(), Comments.empty());
        press(screen, NavKey.ENTER);
        type(screen, "X");
        press(screen, NavKey.ENTER);

        screen.handle(NavKey.CHAR, 's');

        List<String> lines = screen.lines(100, 0);
        assertTrue(anyContains(lines, "save preview"), lines.toString());
        assertTrue(anyContains(lines, "unchanged line"), lines.toString());
        assertTrue(anyContains(lines, "description: demoX"), lines.toString());
    }

    @Test
    void mapRowsShowKeyValueAndCommentColumns() throws Exception {
        Comments comments = new Comments(
                map("env.A", "first"), Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
        EditScreen screen = open(map("A", "1", "LONGKEY", "2"), list(), comments);
        tabTo(screen, 3);
        press(screen, NavKey.RIGHT);

        List<String> lines = screen.lines(120, 0);

        assertTrue(anyContains(lines, "key"), lines.toString());
        assertTrue(anyContains(lines, "value"), lines.toString());
        assertTrue(anyContains(lines, "comment"), lines.toString());
        assertTrue(anyContains(lines, "A"), lines.toString());
        assertTrue(anyContains(lines, "LONGKEY"), lines.toString());
        assertTrue(anyContains(lines, "first"), lines.toString());
    }

    @Test
    void listRowsShowValueAndCommentColumns() throws Exception {
        Comments comments = new Comments(
                map("path-links[./a:/opt/a]", "link to a"),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
        EditScreen screen = open(map("A", "1"), list("./a:/opt/a"), comments);
        tabTo(screen, 6);
        press(screen, NavKey.RIGHT);

        List<String> lines = screen.lines(120, 0);

        assertTrue(anyContains(lines, "./a:/opt/a"), lines.toString());
        assertTrue(anyContains(lines, "link to a"), lines.toString());
    }

    @Test
    void enterEditsValueOnlyAndKeepsTheKey() throws Exception {
        EditScreen screen = open(map("A", "1"), list(), Comments.empty());
        tabTo(screen, 3);
        press(screen, NavKey.RIGHT);
        press(screen, NavKey.ENTER);
        press(screen, NavKey.BACKSPACE);
        type(screen, "9");
        press(screen, NavKey.ENTER);

        assertEquals(java.util.Collections.singletonList("A=9"), values(screen, "env"));
    }

    @Test
    void kEditsTheKeyAndKeepsTheValue() throws Exception {
        EditScreen screen = open(map("A", "1"), list(), Comments.empty());
        tabTo(screen, 3);
        press(screen, NavKey.RIGHT);
        screen.handle(NavKey.CHAR, 'k');
        press(screen, NavKey.BACKSPACE);
        type(screen, "B");
        press(screen, NavKey.ENTER);

        assertEquals(java.util.Collections.singletonList("B=1"), values(screen, "env"));
    }

    @Test
    void shiftJMovesTheSelectedRowDown() throws Exception {
        EditScreen screen = open(map("A", "1", "B", "2"), list(), Comments.empty());
        tabTo(screen, 3);
        press(screen, NavKey.RIGHT);

        screen.handle(NavKey.CHAR, 'J');

        assertEquals(list("B=2", "A=1"), values(screen, "env"));
    }

    @Test
    void shiftKMovesTheSelectedRowUp() throws Exception {
        EditScreen screen = open(map("A", "1", "B", "2"), list(), Comments.empty());
        tabTo(screen, 3);
        press(screen, NavKey.RIGHT);
        press(screen, NavKey.DOWN);

        screen.handle(NavKey.CHAR, 'K');

        assertEquals(list("B=2", "A=1"), values(screen, "env"));
    }
}
