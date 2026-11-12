package io.xac.systemd.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlCommentScannerTest {

    @TempDir
    Path pkg;

    // ---------------------------------------------------------------- helpers

    private ServiceSpec.Builder builder() {
        return ServiceSpec.builder(pkg);
    }

    private static Map<String, String> map(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private String minimalRender() {
        return SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());
    }

    private SpecLoader.Result load(String yaml) {
        try {
            Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return SpecLoader.load(pkg);
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ------------------------------------------------------------------ scan

    @Test
    void keepsInlineCommentOnScalar() {
        Comments comments = YamlCommentScanner.scan("name: demo  # service name\n");

        assertEquals("service name", comments.inline.get("name"));
        assertTrue(comments.leading.isEmpty(), comments.leading.toString());
        assertTrue(comments.orphans.isEmpty(), comments.orphans.toString());

        String rendered = minimalRender();
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);

        assertTrue(result.text.contains("name: demo  # service name\n"), result.text);
        assertEquals(1, result.kept);
        assertEquals(0, result.dropped);
    }

    @Test
    void keepsLeadingBlockAboveField() {
        Comments comments = YamlCommentScanner.scan("# first line\n# second line\nname: demo\n");

        assertEquals(Arrays.asList("first line", "second line"), comments.leading.get("name"));
        assertTrue(comments.inline.isEmpty(), comments.inline.toString());

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(minimalRender(), comments);

        assertTrue(result.text.startsWith("# first line\n# second line\nname: demo\n"), result.text);
        assertEquals(2, result.kept);
        assertEquals(0, result.dropped);
    }

    @Test
    void keepsSectionHeaderCommentBeforeTheSection() {
        String yaml = "# environment\nenv:\n  A: \"1\"\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        assertEquals(Collections.singletonList("environment"), comments.leading.get("env"));

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map("A", "1"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);

        assertTrue(result.text.contains("\n# environment\nenv:\n  A: \"1\"\n"), result.text);
        assertEquals(1, result.kept);
    }

    @Test
    void keepsListValueCommentKeyedByValue() {
        String yaml = "path-links:\n"
                + "  - ./conf:/opt/conf  # config dir\n"
                + "  - ./logs:/applog/demo\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        assertEquals("config dir", comments.inline.get("path-links[./conf:/opt/conf]"));
        assertEquals(1, comments.inline.size(), comments.inline.toString());

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./conf:/opt/conf", "./logs:/applog/demo"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);

        assertTrue(result.text.contains("  - \"./conf:/opt/conf\"  # config dir\n"), result.text);
        assertEquals(1, result.kept);
    }

    @Test
    void followsListValueAfterReorder() {
        String yaml = "path-links:\n"
                + "  - ./conf:/opt/conf  # config dir\n"
                + "  - ./logs:/applog/demo\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        String reordered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./logs:/applog/demo", "./conf:/opt/conf"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(reordered, comments);

        assertTrue(result.text.contains("  - \"./logs:/applog/demo\"\n"), result.text);
        assertTrue(result.text.contains("  - \"./conf:/opt/conf\"  # config dir\n"), result.text);
        assertEquals(1, result.kept);
    }

    @Test
    void keepsNestedFieldAndListItemComments() {
        String yaml = "artifact:\n"
                + "  jar: app.jar  # the jar\n"
                + "runtime:\n"
                + "  java: /usr/bin/java\n"
                + "  opts:\n"
                + "    - -Xmx512m  # heap\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        assertEquals("the jar", comments.inline.get("artifact.jar"));
        assertEquals("heap", comments.inline.get("runtime.opts[-Xmx512m]"));

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);

        assertTrue(result.text.contains("  jar: app.jar  # the jar\n"), result.text);
        assertTrue(result.text.contains("    - -Xmx512m  # heap\n"), result.text);
        assertEquals(2, result.kept);
    }

    @Test
    void ignoresHashInsideQuotesAndPlainValues() {
        String yaml = "description: \"a # b\"\n"
                + "name: a#b\n"
                + "env:\n"
                + "  K: 'x # y'\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        assertTrue(comments.inline.isEmpty(), comments.inline.toString());
        assertTrue(comments.leading.isEmpty(), comments.leading.toString());
        assertTrue(comments.orphans.isEmpty(), comments.orphans.toString());
        assertEquals(0, comments.total());

        Comments afterQuotedValue = YamlCommentScanner.scan("description: \"a # b\"  # real\n");
        assertEquals("real", afterQuotedValue.inline.get("description"));
        assertEquals(1, afterQuotedValue.total());
    }

    @Test
    void ignoresHashInsideBlockScalar() {
        String yaml = "description: |\n"
                + "  first\n"
                + "  # not a comment\n"
                + "  third\n"
                + "name: demo  # real comment\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        assertEquals("real comment", comments.inline.get("name"));
        assertEquals(1, comments.inline.size(), comments.inline.toString());
        assertTrue(comments.leading.isEmpty(), comments.leading.toString());
        assertTrue(comments.orphans.isEmpty(), comments.orphans.toString());

        Comments folded = YamlCommentScanner.scan("description: >\n  # still text\nname: demo\n");
        assertEquals(0, folded.total(), folded.toString());
    }

    @Test
    void reportsTrailingCommentAsOrphanWithLineNumber() {
        Comments comments = YamlCommentScanner.scan("name: demo\n# trailing one\n# trailing two\n");

        assertEquals(2, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).startsWith("line 2: "), comments.orphans.get(0));
        assertTrue(comments.orphans.get(0).endsWith("trailing one"), comments.orphans.get(0));
        assertTrue(comments.orphans.get(1).startsWith("line 3: "), comments.orphans.get(1));

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(minimalRender(), comments);
        assertEquals(0, result.kept);
        assertEquals(2, result.dropped);
        assertFalse(result.text.contains("trailing"), result.text);

        String second = YamlCommentScanner.apply(minimalRender(),
                YamlCommentScanner.scan(result.text)).text;
        assertEquals(result.text, second);
    }

    @Test
    void reportsBlankSeparatedBlockAsOrphan() {
        Comments comments = YamlCommentScanner.scan("# detached\n\nname: demo\n");

        assertEquals(1, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).startsWith("line 1: "), comments.orphans.get(0));
        assertTrue(comments.leading.isEmpty(), comments.leading.toString());
    }

    @Test
    void countsCommentsForPathsMissingFromTheRenderedText() {
        String yaml = "env:\n"
                + "  GONE: x  # stale env\n"
                + "workdir: run  # stale workdir\n"
                + "name: demo\n";
        Comments comments = YamlCommentScanner.scan(yaml);

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(minimalRender(), comments);

        assertEquals(0, result.kept);
        assertEquals(2, result.dropped);
        assertFalse(result.text.contains("#"), result.text);
    }

    @Test
    void countsSectionHeaderForMissingSectionAsDropped() {
        Comments comments = YamlCommentScanner.scan("# env header\nenv:\n  A: \"1\"\n");

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(minimalRender(), comments);

        assertEquals(0, result.kept);
        assertEquals(1, result.dropped);
        assertFalse(result.text.contains("# env header"), result.text);
    }

    // ----------------------------------------------------------------- apply

    @Test
    void mergesEveryCommentShapeIntoRenderedText() {
        String old = "# demo service package\n"
                + "name: demo\n"
                + "description: demo service\n"
                + "\n"
                + "artifact:\n"
                + "  # the jar to run\n"
                + "  jar: app.jar\n"
                + "\n"
                + "runtime:\n"
                + "  # JVM options\n"
                + "  opts:\n"
                + "    - -Xmx512m  # heap\n"
                + "    - -Dx=1\n"
                + "  java: /usr/bin/java\n"
                + "\n"
                + "env:\n"
                + "  B: \"2\"\n"
                + "  A: \"1\"  # first variable\n"
                + "\n"
                + "properties:\n"
                + "  k: v\n"
                + "\n"
                + "workdir: run\n"
                + "\n"
                + "path-links:\n"
                + "  - ./conf:/opt/conf  # config\n"
                + "  - ./logs:/applog/demo\n"
                + "\n"
                + "logs:\n"
                + "  file: /var/log/demo/app.log\n"
                + "\n"
                + "lifecycle:\n"
                + "  auto-start: true\n";

        Comments comments = YamlCommentScanner.scan(old);
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m", "-Dx=1"))
                .env(map("A", "1", "B", "2"))
                .properties(map("k", "v"))
                .workdir("run")
                .pathLinks(Arrays.asList("./conf:/opt/conf", "./logs:/applog/demo"))
                .logFile("/var/log/demo/app.log")
                .build());

        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);
        String expected = "# demo service package\n"
                + "name: demo\n"
                + "description: demo service\n"
                + "\n"
                + "artifact:\n"
                + "  # the jar to run\n"
                + "  jar: app.jar\n"
                + "\n"
                + "runtime:\n"
                + "  java: /usr/bin/java\n"
                + "  # JVM options\n"
                + "  opts:\n"
                + "    - -Xmx512m  # heap\n"
                + "    - -Dx=1\n"
                + "\n"
                + "env:\n"
                + "  A: \"1\"  # first variable\n"
                + "  B: \"2\"\n"
                + "\n"
                + "properties:\n"
                + "  k: v\n"
                + "\n"
                + "workdir: run\n"
                + "\n"
                + "path-links:\n"
                + "  - \"./conf:/opt/conf\"  # config\n"
                + "  - \"./logs:/applog/demo\"\n"
                + "\n"
                + "logs:\n"
                + "  file: /var/log/demo/app.log\n"
                + "\n"
                + "lifecycle:\n"
                + "  auto-start: true\n"
                + "  restart: on-failure\n"
                + "  restart-sec: 5\n"
                + "  timeout-stop-sec: 60\n";

        assertEquals(expected, result.text);
        assertEquals(6, result.kept);
        assertEquals(0, result.dropped);
    }

    @Test
    void mergedTextStillLoadsIntoTheSameSpec() {
        String old = "# title\nname: demo\n"
                + "artifact:\n  jar: app.jar  # jar\n"
                + "runtime:\n  java: /usr/bin/java\n  opts:\n    - -Xmx512m  # heap\n"
                + "env:\n  A: \"1\"  # a\n"
                + "path-links:\n  - ./conf:/opt/conf  # conf\n";
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .env(map("A", "1"))
                .pathLinks(Arrays.asList("./conf:/opt/conf"))
                .build());

        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered,
                YamlCommentScanner.scan(old));

        SpecLoader.Result fromMerged = load(result.text);
        assertTrue(fromMerged.violations.isEmpty(), fromMerged.violations.toString());
        assertEquals(rendered, SpecWriter.render(fromMerged.spec),
                "comments must not change the loaded spec");

        SpecLoader.Result fromRendered = load(rendered);
        assertTrue(fromRendered.violations.isEmpty(), fromRendered.violations.toString());
        assertEquals(fromRendered.spec.name, fromMerged.spec.name);
        assertEquals(fromRendered.spec.jar, fromMerged.spec.jar);
        assertEquals(fromRendered.spec.opts, fromMerged.spec.opts);
        assertEquals(fromRendered.spec.env, fromMerged.spec.env);
        assertEquals(fromRendered.spec.pathLinks, fromMerged.spec.pathLinks);
    }

    @Test
    void applyingTwiceIsStable() {
        String old = "# title\nname: demo  # named\n\nartifact:\n  # jar doc\n  jar: app.jar\n"
                + "path-links:\n  - ./conf:/opt/conf  # conf\n";
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./conf:/opt/conf"))
                .build());

        YamlCommentScanner.ApplyResult first =
                YamlCommentScanner.apply(rendered, YamlCommentScanner.scan(old));
        YamlCommentScanner.ApplyResult second =
                YamlCommentScanner.apply(rendered, YamlCommentScanner.scan(first.text));

        assertEquals(first.text, second.text);
        assertEquals(first.kept, second.kept);
        assertEquals(0, second.dropped);
        assertEquals(1, count(second.text, "# title"), second.text);
        assertEquals(1, count(second.text, "# jar doc"), second.text);
    }

    @Test
    void appliedTextKeepsLayoutInvariants() {
        String old = "# title\nname: demo\n";
        String rendered = minimalRender();
        String text = YamlCommentScanner.apply(rendered, YamlCommentScanner.scan(old)).text;

        assertTrue(text.endsWith("\n"), text);
        assertFalse(text.endsWith("\n\n"), text);
        assertFalse(text.contains("\r"), text);
        for (String line : text.split("\n")) {
            assertFalse(line.endsWith(" "), "trailing space in [" + line + "]");
        }
    }

    @Test
    void emptyCommentsLeaveTheRenderedTextUnchanged() {
        String rendered = minimalRender();

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(rendered, Comments.empty());

        assertEquals(rendered, result.text);
        assertEquals(0, result.kept);
        assertEquals(0, result.dropped);

        Comments empty = YamlCommentScanner.scan("");
        assertTrue(empty.inline.isEmpty());
        assertTrue(empty.leading.isEmpty());
        assertTrue(empty.orphans.isEmpty());
        assertEquals(0, empty.total());
    }

    @Test
    void scanIgnoresDocumentMarkersAndUnknownLines() {
        Comments comments = YamlCommentScanner.scan("---\nname: demo\n...\n# tail\n");

        assertEquals(0, comments.inline.size(), comments.inline.toString());
        assertEquals(1, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).contains("tail"), comments.orphans.get(0));
    }

    @Test
    void quotedListValueAndScannerKeyAgree() {
        String old = "path-links:\n  - \"./conf:/opt/conf\"  # conf\n";
        Comments comments = YamlCommentScanner.scan(old);

        List<String> keys = new java.util.ArrayList<String>(comments.inline.keySet());
        assertEquals(Collections.singletonList("path-links[./conf:/opt/conf]"), keys);
    }
}
