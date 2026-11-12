package io.xac.systemd.tui.edit;

import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.Schema;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecLoader;
import io.xac.systemd.spec.SpecWriter;
import io.xac.systemd.spec.Violation;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /** 写 yaml 到包目录，返回写入的文本。 */
    private String writeYaml(ServiceSpec spec) throws IOException {
        String text = SpecWriter.render(spec);
        Files.write(pkg.resolve("xac.yaml"), text.getBytes(StandardCharsets.UTF_8));
        return text;
    }

    private ServiceSpec reload() throws IOException {
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertTrue(result.ok(), String.valueOf(result.violations));
        return result.spec;
    }

    private static int countPath(List<Violation> violations, String path) {
        int count = 0;
        for (Violation violation : violations) {
            if (path.equals(violation.path)) {
                count++;
            }
        }
        return count;
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

    // ------------------------------------------------- fix round 1 regressions

    @Test
    void preservesRawWhitespaceThroughRenderAndReload() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar(" app.jar")
                .java("/usr/bin/java")
                .opts(list(" -Xmx1g "))
                .env(map("FOO", "bar "))
                .properties(map("k ", " v "))
                .pathLinks(list(" ./conf:/opt/x "))
                .logFile("/var/log/x.log ")
                .build();
        String text = writeYaml(spec);
        ServiceSpec loaded = reload();
        EditorModel model = EditorModel.from(loaded, Comments.empty());

        assertEquals(list(" app.jar"), model.field("artifact.jar").values);
        assertEquals(list(" -Xmx1g "), model.field("runtime.opts").values);
        assertEquals(list("FOO=bar "), model.field("env").values);
        assertEquals(list("k = v "), model.field("properties").values);
        assertEquals(list(" ./conf:/opt/x "), model.field("path-links").values);
        assertEquals(list("/var/log/x.log "), model.field("logs.file").values);

        ServiceSpec back = model.toSpec();
        assertEquals(spec.jar, back.jar);
        assertEquals(list(" -Xmx1g "), back.opts);
        assertEquals("bar ", back.env.get("FOO"));
        assertEquals(" v ", back.properties.get("k "));
        assertEquals(list(" ./conf:/opt/x "), back.pathLinks);
        assertEquals("/var/log/x.log ", back.logFile);

        // 再次渲染逐字节相同，且没有改写磁盘上的文件。
        assertEquals(text, SpecWriter.render(back));
        assertEquals(text, new String(
                Files.readAllBytes(pkg.resolve("xac.yaml")), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMapKeyContainingEquals() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("a=b", "v2"))
                .build();
        writeYaml(spec);
        EditorModel model = EditorModel.from(reload(), Comments.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                model::toSpec);
        assertTrue(message(e).contains("properties"), message(e));
        assertTrue(message(e).contains("a=b"), message(e));

        // validate 不抛异常，而是把冲突折成 violation。
        List<Violation> violations = model.validate();
        boolean conflict = false;
        for (Violation violation : violations) {
            if ("properties".equals(violation.path)
                    && violation.message.contains("a=b")) {
                conflict = true;
            }
        }
        assertTrue(conflict, String.valueOf(violations));

        // 用户重写该字段后即可保存。
        ServiceSpec fixed = model.with("properties", list("a=v2")).toSpec();
        assertEquals("v2", fixed.properties.get("a"));
    }

    @Test
    void validateReportsRequiredEmptiesInsteadOfThrowing() {
        EditorModel model = EditorModel.from(ServiceSpec.builder(pkg).build(),
                Comments.empty());

        List<Violation> violations = model.validate();
        assertEquals(3, violations.size(), String.valueOf(violations));
        assertEquals("name", violations.get(0).path);
        assertEquals("artifact.jar", violations.get(1).path);
        assertEquals("runtime.java", violations.get(2).path);
        for (Violation violation : violations) {
            assertEquals("must not be empty", violation.message);
        }
    }

    @Test
    void validateTurnsDraftErrorsIntoViolations() {
        EditorModel base = EditorModel.from(fullSpec(), Comments.empty());

        List<Violation> integer = base.with("lifecycle.restart-sec", list("12x")).validate();
        assertEquals(1, integer.size(), String.valueOf(integer));
        assertEquals("lifecycle.restart-sec", integer.get(0).path);
        assertTrue(integer.get(0).message.contains("12x"), integer.get(0).message);

        List<Violation> map = base.with("env", list("NOEQUALS")).validate();
        assertEquals(1, map.size(), String.valueOf(map));
        assertEquals("env", map.get(0).path);
        assertTrue(map.get(0).message.contains("line 1"), map.get(0).message);
    }

    @Test
    void keepsEmptyMapKeyAndRoundTripsIt() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("", "v"))
                .build();
        String text = writeYaml(spec);
        EditorModel model = EditorModel.from(reload(), Comments.empty());

        assertEquals(list("=v"), model.field("properties").values);
        ServiceSpec back = model.toSpec();
        assertEquals("v", back.properties.get(""));
        assertEquals(text, SpecWriter.render(back));
    }

    @Test
    void keepsMapConflictUntilTheConflictingRowIsRewritten() throws IOException {
        writeYaml(ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("a=b", "v"))
                .build());
        EditorModel model = EditorModel.from(reload(), Comments.empty());

        // UI 提交整份 map，冲突行仍在：不得静默拆成 {a: b=v}。
        EditorModel same = model.with("properties", list("a=b=v", "C=1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, same::toSpec);
        assertTrue(message(e).contains("properties"), message(e));
        assertEquals(1, countPath(same.validate(), "properties"), String.valueOf(same.validate()));

        // 改别的字段不能顺手清掉 properties 的冲突。
        EditorModel otherField = model.with("env", list("X=1"));
        assertEquals(1, countPath(otherField.validate(), "properties"),
                String.valueOf(otherField.validate()));

        // 重写冲突行本身才解除。
        EditorModel fixed = model.with("properties", list("a=v2"));
        assertTrue(fixed.validate().isEmpty(), String.valueOf(fixed.validate()));
        assertEquals("v2", fixed.toSpec().properties.get("a"));
    }

    @Test
    void reportsEveryConflictingMapKey() throws IOException {
        writeYaml(ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("a=b", "1", "c=d", "2"))
                .build());
        EditorModel model = EditorModel.from(reload(), Comments.empty());

        assertEquals(2, countPath(model.validate(), "properties"),
                String.valueOf(model.validate()));
    }

    @Test
    void validateNeverThrowsOnUnparseablePathLinks() {
        EditorModel base = EditorModel.from(fullSpec(), Comments.empty());

        // Windows 上尾随空格非法（报 violation），Linux 上合法（无 violation）；都不许抛。
        EditorModel trailing = base.with("path-links", list(" ./conf:/opt/x "));
        List<Violation> trailingViolations = trailing.validate();
        assertTrue(trailingViolations.isEmpty()
                        || countPath(trailingViolations, "path-links[0]") == 1,
                String.valueOf(trailingViolations));

        // NUL 在两个平台上都不是合法路径字符。
        EditorModel nul = base.with("path-links", list("conf:/opt/x\u0000"));
        List<Violation> nulViolations = nul.validate();
        assertEquals(1, countPath(nulViolations, "path-links[0]"),
                String.valueOf(nulViolations));
        assertTrue(nulViolations.get(0).message.contains("invalid target path"),
                nulViolations.get(0).message);
    }

    @Test
    void collectsErrorsFromSeveralFieldsAtOnce() {
        EditorModel model = EditorModel.from(fullSpec(), Comments.empty())
                .with("lifecycle.restart-sec", list("12x"))
                .with("env", list("NOEQUALS"));

        List<Violation> violations = model.validate();
        assertEquals(2, violations.size(), String.valueOf(violations));
        assertEquals("env", violations.get(0).path, String.valueOf(violations));
        assertEquals("lifecycle.restart-sec", violations.get(1).path,
                String.valueOf(violations));
    }

    @Test
    void leavesTheFileUntouchedWhenNothingChanged() throws IOException {
        ServiceSpec spec = ServiceSpec.builder(pkg)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(list("./conf:/opt/demo/conf"))
                // Windows 上 /var/log/... 不算绝对路径；用包目录下的绝对路径，两个平台都合法。
                .logFile(pkg.resolve("app.log").toString())
                .build();
        writeYaml(spec);
        Path file = pkg.resolve("xac.yaml");
        byte[] before = Files.readAllBytes(file);

        EditorModel model = EditorModel.from(reload(), Comments.empty());
        assertTrue(model.validate().isEmpty(), String.valueOf(model.validate()));

        ServiceSpec back = model.toSpec();
        SpecWriter.save(file, SpecWriter.render(back));
        assertArrayEquals(before, Files.readAllBytes(file));
        SpecWriter.save(file, SpecWriter.render(back));
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
