package io.xac.systemd.tui.edit;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.Schema;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器模型契约：字段顺序、spec 往返、校验路径、非法输入的 IAE 消息。
 */
class EditorModelTest {

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
                .opts(list("-Xmx256m", "-Dfoo=bar"))
                .env(map("GREETING", "hello", "PORT", "8080"))
                .properties(map("server.port", "8080"))
                .workdir("run")
                .pathLinks(list("./conf:/opt/demo/conf"))
                .logFile("/var/log/demo/app.log")
                .lifecycle(new Lifecycle(Boolean.TRUE, "on-failure", 5, 60))
                .build();
    }

    private static String message(IllegalArgumentException e) {
        String message = e.getMessage();
        return message == null ? "" : message;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void exposesAllEditableFieldsInFixedOrder() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty());

        List<String> paths = new ArrayList<String>();
        for (EditorField field : model.fields()) {
            paths.add(field.path);
        }
        assertEquals(Arrays.asList(
                "name", "description", "artifact.jar", "runtime.java", "runtime.opts",
                "env", "properties", "workdir", "path-links", "logs.file",
                "lifecycle.auto-start", "lifecycle.restart", "lifecycle.restart-sec",
                "lifecycle.timeout-stop-sec"), paths);
        assertEquals(14, model.fields().size());

        for (EditorField field : model.fields()) {
            assertEquals("name".equals(field.path), field.readOnly, field.path);
            assertFalse(field.label.isEmpty(), field.path);
        }
        assertEquals(Schema.Type.STRING, model.field("name").type);
        assertEquals(Schema.Type.STRING_LIST, model.field("runtime.opts").type);
        assertEquals(Schema.Type.STRING_MAP, model.field("env").type);
        assertEquals(Schema.Type.BOOLEAN, model.field("lifecycle.auto-start").type);
        assertEquals(Schema.Type.INTEGER, model.field("lifecycle.restart-sec").type);

        List<String> restart = model.field("lifecycle.restart").options;
        assertEquals(8, restart.size());
        assertEquals("", restart.get(0));
        assertTrue(restart.contains("on-failure"));
        assertTrue(model.field("description").options.isEmpty());
    }

    @Test
    void mapsListsAndMapsBackToSpec() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty());
        EditorModel draft = model
                .with("runtime.opts", list("-Xms64m", "-Xmx512m"))
                .with("env", list("A=1", "B=two=2"))
                .with("properties", list("a.b=c d"));

        ServiceSpec out = draft.toSpec();
        assertEquals(list("-Xms64m", "-Xmx512m"), out.opts);
        assertEquals(list("A", "B"), new ArrayList<String>(out.env.keySet()));
        assertEquals("1", out.env.get("A"));
        assertEquals("two=2", out.env.get("B"));
        assertEquals("c d", out.properties.get("a.b"));

        // 未改动的字段原样带过去。
        assertEquals("demo", out.name);
        assertEquals(pkg.resolve("app.jar").normalize(), out.jar);
        assertEquals("/usr/bin/java", out.java);
        assertEquals(pkg.resolve("run").normalize(), out.workdir);
        assertEquals(list("./conf:/opt/demo/conf"), out.pathLinks);
        assertEquals("/var/log/demo/app.log", out.logFile);
        assertEquals(Boolean.TRUE, out.lifecycle.autoStart);
        assertEquals("on-failure", out.lifecycle.restart);
        assertEquals(Integer.valueOf(5), out.lifecycle.restartSec);
        assertEquals(Integer.valueOf(60), out.lifecycle.timeoutStopSec);

        // 原对象不受影响。
        assertEquals(list("-Xmx256m", "-Dfoo=bar"), model.field("runtime.opts").values);
    }

    @Test
    void turnsCommentedOutAndAbsentFieldsBackIntoDefaults() {
        ServiceSpec minimal = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build();
        EditorModel model = EditorModel.from(minimal, Comments.empty());

        assertEquals(list(""), model.field("description").values);
        assertEquals(Collections.<String>emptyList(), model.field("runtime.opts").values);
        assertEquals(Collections.<String>emptyList(), model.field("env").values);
        assertEquals(Collections.<String>emptyList(), model.field("properties").values);
        assertEquals(list(""), model.field("workdir").values);
        assertEquals(Collections.<String>emptyList(), model.field("path-links").values);
        assertEquals(list(""), model.field("logs.file").values);
        assertEquals(list(""), model.field("lifecycle.auto-start").values);
        assertEquals(list(""), model.field("lifecycle.restart").values);
        assertEquals(list(""), model.field("lifecycle.restart-sec").values);
        assertEquals(list(""), model.field("lifecycle.timeout-stop-sec").values);

        ServiceSpec out = model.toSpec();
        assertEquals(null, out.workdir);
        assertEquals(null, out.logFile);
        assertEquals(null, out.lifecycle.autoStart);
        assertEquals(null, out.lifecycle.restart);
        assertEquals(null, out.lifecycle.restartSec);
        assertEquals(null, out.lifecycle.timeoutStopSec);
        assertTrue(out.env.isEmpty());
        assertTrue(out.properties.isEmpty());
        assertTrue(out.opts.isEmpty());
    }

    @Test
    void rendersPackageRelativePathsWithForwardSlashes() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty());
        assertEquals(list("app.jar"), model.field("artifact.jar").values);
        assertEquals(list("run"), model.field("workdir").values);

        ServiceSpec outside = ServiceSpec.builder(pkg)
                .name("demo")
                .jar(pkg.getParent().resolve("elsewhere.jar").toString())
                .java("/usr/bin/java")
                .build();
        String absolute = outside.jar.toString();
        assertEquals(list(absolute),
                EditorModel.from(outside, Comments.empty()).field("artifact.jar").values);
    }

    @Test
    void validatesPlaceholderReferencesWithFieldPath() {
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("server.port", "${MISSING_PORT}"))
                .build();
        EditorModel model = EditorModel.from(spec, Comments.empty());

        List<Violation> violations = model.validate();
        Violation placeholder = null;
        for (Violation violation : violations) {
            if ("properties.server.port".equals(violation.path)) {
                placeholder = violation;
            }
        }
        assertTrue(placeholder != null, "expected properties.server.port in " + violations);
        assertTrue(placeholder.message.contains("${MISSING_PORT}"), placeholder.message);

        // 定义 env 后同一路径的占位符错误消失。
        List<Violation> fixed = model.with("env", list("MISSING_PORT=8080")).validate();
        for (Violation violation : fixed) {
            assertFalse("properties.server.port".equals(violation.path), violation.toString());
        }
    }

    @Test
    void rejectsMalformedMapRow() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty())
                .with("env", list("GOOD=1", "novalue"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, model::toSpec);
        assertTrue(message(e).contains("env"), message(e));
        assertTrue(message(e).contains("line 2"), message(e));

        EditorModel duplicate = EditorModel.from(fullSpec(), Comments.empty())
                .with("properties", list("a=1", "a=2"));
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                duplicate::toSpec);
        assertTrue(message(dup).contains("properties"), message(dup));
        assertTrue(message(dup).contains("a"), message(dup));
    }

    @Test
    void rejectsInvalidBoolean() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty())
                .with("lifecycle.auto-start", list("yes"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, model::toSpec);
        assertTrue(message(e).contains("lifecycle.auto-start"), message(e));
        assertTrue(message(e).contains("yes"), message(e));

        ServiceSpec out = EditorModel.from(fullSpec(), Comments.empty())
                .with("lifecycle.auto-start", list("false"))
                .toSpec();
        assertEquals(Boolean.FALSE, out.lifecycle.autoStart);
    }

    @Test
    void rejectsNonNumericInteger() {
        EditorModel letters = EditorModel.from(fullSpec(), Comments.empty())
                .with("lifecycle.restart-sec", list("abc"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, letters::toSpec);
        assertTrue(message(e).contains("lifecycle.restart-sec"), message(e));
        assertTrue(message(e).contains("abc"), message(e));

        EditorModel zero = EditorModel.from(fullSpec(), Comments.empty())
                .with("lifecycle.timeout-stop-sec", list("0"));
        IllegalArgumentException zeroError = assertThrows(IllegalArgumentException.class,
                zero::toSpec);
        assertTrue(message(zeroError).contains("lifecycle.timeout-stop-sec"), message(zeroError));
        assertTrue(message(zeroError).contains("0"), message(zeroError));
    }

    @Test
    void rejectsEmptyListItemsAndTargetlessLinks() {
        EditorModel emptyOpt = EditorModel.from(fullSpec(), Comments.empty())
                .with("runtime.opts", list("-Xmx1g", "   "));
        IllegalArgumentException optError = assertThrows(IllegalArgumentException.class,
                emptyOpt::toSpec);
        assertTrue(message(optError).contains("runtime.opts"), message(optError));
        assertTrue(message(optError).contains("line 2"), message(optError));

        EditorModel badLink = EditorModel.from(fullSpec(), Comments.empty())
                .with("path-links", list("nocolon"));
        IllegalArgumentException linkError = assertThrows(IllegalArgumentException.class,
                badLink::toSpec);
        assertTrue(message(linkError).contains("path-links"), message(linkError));
    }

    @Test
    void withIsImmutableAndRejectsReadOnlyOrUnknownFields() {
        EditorModel base = EditorModel.from(fullSpec(), Comments.empty());
        EditorModel changed = base.with("description", list("changed"));

        assertNotSame(base, changed);
        assertEquals(list("changed"), changed.field("description").values);
        assertEquals(list("demo service"), base.field("description").values);

        assertThrows(UnsupportedOperationException.class,
                () -> base.fields().add(base.field("name")));
        assertThrows(UnsupportedOperationException.class,
                () -> base.field("description").values.add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> base.field("lifecycle.restart").options.add("x"));

        IllegalArgumentException readOnly = assertThrows(IllegalArgumentException.class,
                () -> base.with("name", list("renamed")));
        assertTrue(message(readOnly).contains("name"), message(readOnly));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> base.field("nope"));
        assertTrue(message(unknown).contains("nope"), message(unknown));
        assertThrows(IllegalArgumentException.class, () -> base.with("nope", list("x")));
    }

    @Test
    void prefersLeadingCommentOverInlineForHelp() {
        Map<String, String> inline = new LinkedHashMap<String, String>();
        inline.put("runtime.java", "inline java");
        inline.put("description", "inline description");
        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        leading.put("runtime.java", list("leading java"));
        leading.put("description", list("first line", "second line"));
        Comments comments = new Comments(inline, leading, Collections.<String>emptyList());

        EditorModel model = EditorModel.from(fullSpec(), comments);
        assertEquals("leading java", model.field("runtime.java").help);
        assertEquals("first line\nsecond line", model.field("description").help);
        assertEquals("inline only",
                EditorModel.from(fullSpec(), new Comments(
                        map("logs.file", "inline only"),
                        Collections.<String, List<String>>emptyMap(),
                        Collections.<String>emptyList())).field("logs.file").help);
        assertEquals("", model.field("artifact.jar").help);
    }
}
