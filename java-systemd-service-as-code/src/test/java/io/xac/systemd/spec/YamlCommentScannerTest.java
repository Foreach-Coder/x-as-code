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
        String old = "# title\nname: demo\ndescription: demo service\n"
                + "artifact:\n  jar: app.jar  # jar\n"
                + "runtime:\n  java: /usr/bin/java\n  opts:\n    - -Xmx512m  # heap\n"
                + "env:\n  A: \"1\"  # a\n"
                + "properties:\n  k: v  # prop\n"
                + "workdir: run\n"
                + "path-links:\n  - ./conf:/opt/conf  # conf\n"
                + "logs:\n  file: /var/log/demo/app.log  # log\n"
                + "lifecycle:\n  auto-start: false  # no boot\n"
                + "  restart: always\n  restart-sec: 30\n  timeout-stop-sec: 90\n";
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .env(map("A", "1"))
                .properties(map("k", "v"))
                .workdir("run")
                .pathLinks(Arrays.asList("./conf:/opt/conf"))
                .logFile("/var/log/demo/app.log")
                .lifecycle(new Lifecycle(Boolean.FALSE, "always", 30, 90))
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
        assertEquals(fromRendered.spec.description, fromMerged.spec.description);
        assertEquals(fromRendered.spec.jar, fromMerged.spec.jar);
        assertEquals(fromRendered.spec.java, fromMerged.spec.java);
        assertEquals(fromRendered.spec.opts, fromMerged.spec.opts);
        assertEquals(fromRendered.spec.env, fromMerged.spec.env);
        assertEquals(fromRendered.spec.properties, fromMerged.spec.properties);
        assertEquals(fromRendered.spec.workdir, fromMerged.spec.workdir);
        assertEquals(fromRendered.spec.pathLinks, fromMerged.spec.pathLinks);
        assertEquals(fromRendered.spec.logFile, fromMerged.spec.logFile);
        assertEquals(fromRendered.spec.lifecycle.autoStart, fromMerged.spec.lifecycle.autoStart);
        assertEquals(fromRendered.spec.lifecycle.restart, fromMerged.spec.lifecycle.restart);
        assertEquals(fromRendered.spec.lifecycle.restartSec, fromMerged.spec.lifecycle.restartSec);
        assertEquals(fromRendered.spec.lifecycle.timeoutStopSec,
                fromMerged.spec.lifecycle.timeoutStopSec);
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
                YamlCommentScanner.apply(first.text, YamlCommentScanner.scan(first.text));

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

    // --------------------------------------------------------------- template

    @Test
    void scanTemplateAttributesEveryProseBlockToItsSection() {
        Comments template = YamlCommentScanner.scanTemplate();

        assertTrue(template.inline.isEmpty(), template.inline.toString());
        assertTrue(template.orphans.isEmpty(), template.orphans.toString());
        assertTrue(joinLines(template.leading.get("name")).contains("single source of truth"),
                template.leading.toString());
        assertEquals("xac.yaml -- service definition for java-systemd-service-as-code.",
                template.leading.get("name").get(0));
        assertTrue(joinLines(template.leading.get("artifact.jar")).contains("Application jar"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("runtime.java")).contains("Java executable"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("runtime.opts")).contains("JVM arguments"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("env")).contains("Environment variables"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("properties")).contains("System properties"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("workdir")).contains("Working directory"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("path-links")).contains("Symlinks created"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("logs")).contains("journald"),
                template.leading.toString());
        assertTrue(joinLines(template.leading.get("lifecycle.auto-start"))
                .contains("starting at boot"), template.leading.toString());
    }

    @Test
    void templateCommentsSurviveFirstSaveOfFullSpec() {
        String rendered = SpecWriter.render(fullSpec());
        Comments template = YamlCommentScanner.scanTemplate();

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(rendered, Comments.empty(), template);

        assertTrue(result.text.contains("single source of truth"), result.text);
        assertTrue(result.text.contains("Environment variables"), result.text);
        assertTrue(result.text.contains("System properties"), result.text);
        assertTrue(result.text.contains("Working directory"), result.text);
        assertTrue(result.text.contains("Symlinks created"), result.text);
        assertTrue(result.text.contains("journald"), result.text);
        assertTrue(result.text.contains("starting at boot"), result.text);
        assertEquals(0, result.dropped);
        assertEquals(template.total(), result.kept);
        assertSameSpec(rendered, result.text);
    }

    @Test
    void sourceCommentsOverrideTemplateOnSamePath() {
        String rendered = SpecWriter.render(fullSpec());
        Comments source = YamlCommentScanner.scan("# my header\nname: demo  # mine\n");

        YamlCommentScanner.ApplyResult result =
                YamlCommentScanner.apply(rendered, source, YamlCommentScanner.scanTemplate());

        assertTrue(result.text.contains("# my header"), result.text);
        assertFalse(result.text.contains("single source of truth"), result.text);
        assertEquals(1, count(result.text, "# my header"), result.text);
        assertEquals(1, count(result.text, "# mine"), result.text);
        assertTrue(result.text.contains("Application jar"), result.text);
        assertEquals(0, result.dropped);
        assertSameSpec(rendered, result.text);
    }

    @Test
    void templateDefaultsAreNotCountedAsDropped() {
        String rendered = minimalRender();
        Comments template = YamlCommentScanner.scanTemplate();

        YamlCommentScanner.ApplyResult noUserComments =
                YamlCommentScanner.apply(rendered, Comments.empty(), template);
        assertEquals(0, noUserComments.dropped);
        assertTrue(noUserComments.kept > 0);
        assertTrue(noUserComments.kept < template.total(),
                "sections missing from the spec are simply not rendered");

        Comments userCommentOnMissingField =
                YamlCommentScanner.scan("name: demo\n# lost\ndescription: d\n");
        YamlCommentScanner.ApplyResult withUserComments =
                YamlCommentScanner.apply(rendered, userCommentOnMissingField, template);
        assertEquals(1, withUserComments.dropped);
    }

    @Test
    void firstSaveOfRealInitFileKeepsTemplateCommentsWithoutFalseDropped() {
        String initYaml = templateWith("demo", "app.jar", "/usr/bin/java");
        Comments source = YamlCommentScanner.scan(initYaml);
        String rendered = SpecWriter.render(fullSpec());

        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(
                rendered, source, YamlCommentScanner.scanTemplate("demo"));

        assertEquals(0, result.dropped, result.text);
        assertEquals(1, count(result.text, "xac.yaml -- service definition"), result.text);
        String[] needles = {
                "Application jar", "Java executable", "JVM arguments",
                "Environment variables", "System properties", "Working directory",
                "Symlinks created", "read journald", "starting at boot"};
        for (String needle : needles) {
            assertTrue(result.text.contains(needle), needle + " missing:\n" + result.text);
        }
        assertFalse(result.text.contains("{{"), result.text);
        assertSameSpec(rendered, result.text);

        YamlCommentScanner.ApplyResult second = YamlCommentScanner.apply(
                rendered, YamlCommentScanner.scan(result.text),
                YamlCommentScanner.scanTemplate("demo"));
        assertEquals(result.text, second.text, "second save must be stable");
    }

    @Test
    void templateCommentsUseTheRealServiceNameAndNeverLeakPlaceholders() {
        String rendered = SpecWriter.render(fullSpec());

        YamlCommentScanner.ApplyResult first = YamlCommentScanner.apply(
                rendered, Comments.empty(), YamlCommentScanner.scanTemplate("demo"));

        assertFalse(first.text.contains("{{"), first.text);
        assertTrue(first.text.contains("/etc/xac/xac.demo.env"), first.text);
        assertFalse(first.text.contains("xac.{{name}}.env"), first.text);

        YamlCommentScanner.ApplyResult second = YamlCommentScanner.apply(
                rendered, YamlCommentScanner.scan(first.text),
                YamlCommentScanner.scanTemplate("demo"));
        assertFalse(second.text.contains("{{"), second.text);
        assertSameSpec(rendered, second.text);
    }

    @Test
    void editedTemplateExampleLineIsCountedAsDropped() {
        String initYaml = templateWith("demo", "app.jar", "/usr/bin/java")
                .replace("/opt/demo/conf", "/opt/prod/conf");
        Comments source = YamlCommentScanner.scan(initYaml);
        String rendered = SpecWriter.render(fullSpec());

        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(
                rendered, source, YamlCommentScanner.scanTemplate("demo"));

        assertEquals(1, result.dropped, result.text);
        assertFalse(result.text.contains("/opt/prod/conf"), result.text);
        assertTrue(result.text.contains("Environment variables"), result.text);
        assertSameSpec(rendered, result.text);
    }

    @Test
    void fileEndCommentedFieldStaysVisibleAsOrphanWithoutFalseDropped() {
        Comments source = YamlCommentScanner.scan(
                "name: demo\n# workdir: /opt/prod/work\n");
        String rendered = SpecWriter.render(fullSpec());

        assertEquals(1, source.orphans.size(), source.orphans.toString());
        assertTrue(source.orphans.get(0).contains("workdir: /opt/prod/work"),
                source.orphans.toString());

        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(
                rendered, source, YamlCommentScanner.scanTemplate("demo"));

        assertEquals(0, result.dropped, result.text);
        assertSameSpec(rendered, result.text);
    }

    // ------------------------------------------------------- duplicate values

    @Test
    void crossFormDuplicateInlineThenLeadingBecomesOrphan() {
        String old = "path-links:\n"
                + "  - ./c:/opt/c  # one\n"
                + "  # two\n"
                + "  - ./c:/opt/c\n";
        assertCrossFormDuplicate(old);
    }

    @Test
    void crossFormDuplicateLeadingThenInlineBecomesOrphan() {
        String old = "path-links:\n"
                + "  # one\n"
                + "  - ./c:/opt/c\n"
                + "  - ./c:/opt/c  # two\n";
        assertCrossFormDuplicate(old);
    }

    private void assertCrossFormDuplicate(String old) {
        Comments comments = YamlCommentScanner.scan(old);

        assertEquals(1, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).contains(
                "duplicate comment for path-links[./c:/opt/c]"), comments.orphans.get(0));
        assertTrue(comments.orphans.get(0).contains("two"), comments.orphans.get(0));

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./c:/opt/c", "./c:/opt/c"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);
        assertEquals(1, result.kept, result.text);
        assertEquals(1, result.dropped, result.text);
        assertEquals(1, count(result.text, "# one"), result.text);
        assertEquals(0, count(result.text, "# two"), result.text);
        assertSameSpec(rendered, result.text);
    }

    @Test
    void duplicateListValueInlineCommentsBecomeOrphans() {
        String old = "path-links:\n"
                + "  - ./c:/opt/c  # one\n"
                + "  - ./c:/opt/c  # two\n";
        Comments comments = YamlCommentScanner.scan(old);

        assertEquals("one", comments.inline.get("path-links[./c:/opt/c]"));
        assertEquals(1, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).contains(
                "duplicate comment for path-links[./c:/opt/c]"), comments.orphans.get(0));
        assertTrue(comments.orphans.get(0).contains("two"), comments.orphans.get(0));

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./c:/opt/c", "./c:/opt/c"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);
        assertEquals(1, result.kept);
        assertEquals(1, result.dropped);
        assertEquals(1, count(result.text, "# one"), result.text);
        assertEquals(0, count(result.text, "# two"), result.text);
        assertSameSpec(rendered, result.text);
    }

    @Test
    void duplicateListValueLeadingBlocksBecomeOrphans() {
        String old = "path-links:\n"
                + "  # first\n"
                + "  - ./c:/opt/c\n"
                + "  # second\n"
                + "  - ./c:/opt/c\n";
        Comments comments = YamlCommentScanner.scan(old);

        assertEquals(Collections.singletonList("first"),
                comments.leading.get("path-links[./c:/opt/c]"));
        assertEquals(1, comments.orphans.size(), comments.orphans.toString());
        assertTrue(comments.orphans.get(0).contains(
                "duplicate comment for path-links[./c:/opt/c]"), comments.orphans.get(0));
        assertTrue(comments.orphans.get(0).contains("second"), comments.orphans.get(0));

        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .pathLinks(Arrays.asList("./c:/opt/c", "./c:/opt/c"))
                .build());
        YamlCommentScanner.ApplyResult result = YamlCommentScanner.apply(rendered, comments);
        assertEquals(1, result.kept);
        assertEquals(1, result.dropped);
    }

    // ------------------------------------------------------------- injection

    @Test
    void programmaticCommentTextCannotSpanLines() {
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .description("safe")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());

        Comments inlineInjection = new Comments(
                map("name", "note\ndescription: evil"),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
        YamlCommentScanner.ApplyResult inlineResult =
                YamlCommentScanner.apply(rendered, inlineInjection);
        assertFalse(inlineResult.text.contains("\ndescription: evil"),
                inlineResult.text);
        assertTrue(inlineResult.text.contains("\\ndescription: evil"), inlineResult.text);
        assertSameSpec(rendered, inlineResult.text);

        Map<String, List<String>> leading = new LinkedHashMap<String, List<String>>();
        leading.put("name", Collections.singletonList("note\u2028description: evil"));
        Comments leadingInjection = new Comments(
                Collections.<String, String>emptyMap(), leading,
                Collections.<String>emptyList());
        YamlCommentScanner.ApplyResult leadingResult =
                YamlCommentScanner.apply(rendered, leadingInjection);
        assertFalse(leadingResult.text.contains("\u2028"), leadingResult.text);
        assertTrue(leadingResult.text.contains("\\u2028"), leadingResult.text);
        assertSameSpec(rendered, leadingResult.text);
    }

    @Test
    void lineSeparatorsInSourceFileRoundTrip() {
        String[] separators = {"\r", "\u0085", "\u2028", "\u2029"};
        for (String separator : separators) {
            String old = "name: demo" + separator + "description: d  # note\n"
                    + "artifact:\n  jar: app.jar\nruntime:\n  java: /usr/bin/java\n";
            String label = "separator U+" + Integer.toHexString(separator.charAt(0));

            SpecLoader.Result source = load(old);
            assertTrue(source.violations.isEmpty(), label + ": " + source.violations);
            assertEquals("d", source.spec.description, label);
            assertEquals("note",
                    YamlCommentScanner.scan(old).inline.get("description"), label);

            String rendered = SpecWriter.render(source.spec);
            YamlCommentScanner.ApplyResult result =
                    YamlCommentScanner.apply(rendered, YamlCommentScanner.scan(old));
            assertSameSpec(rendered, result.text);
        }
    }

    @Test
    void scanStripsByteOrderMark() {
        Comments comments = YamlCommentScanner.scan("\uFEFF# header\nname: demo\n");

        assertEquals(Collections.singletonList("header"), comments.leading.get("name"));
        assertTrue(comments.orphans.isEmpty(), comments.orphans.toString());
    }

    @Test
    void applyingSameCommentsToMergedTextDoesNotDuplicate() {
        String old = "# title\nname: demo  # named\n\nartifact:\n  # jar doc\n  jar: app.jar\n";
        String rendered = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());
        Comments comments = YamlCommentScanner.scan(old);

        YamlCommentScanner.ApplyResult first = YamlCommentScanner.apply(rendered, comments);
        YamlCommentScanner.ApplyResult second = YamlCommentScanner.apply(first.text, comments);

        assertEquals(first.text, second.text);
        assertEquals(first.kept, second.kept);
        assertEquals(0, second.dropped);
        assertEquals(1, count(second.text, "# title"), second.text);
        assertEquals(1, count(second.text, "# jar doc"), second.text);
        assertEquals(1, count(second.text, "# named"), second.text);
    }

    // ---------------------------------------------------------------- helpers

    private ServiceSpec fullSpec() {
        return builder()
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .env(map("A", "1"))
                .properties(map("k", "v"))
                .workdir("run")
                .pathLinks(Arrays.asList("./conf:/opt/conf"))
                .logFile("/var/log/demo/app.log")
                .lifecycle(new Lifecycle(Boolean.TRUE, "on-failure", 5, 60))
                .build();
    }

    private void assertSameSpec(String rendered, String text) {
        SpecLoader.Result loaded = load(text);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertEquals(rendered, SpecWriter.render(loaded.spec),
                "comments must not change the loaded spec");
    }

    private static String templateWith(String name, String jar, String java) {
        return readTemplateResource()
                .replace("{{name}}", name)
                .replace("{{jar}}", jar)
                .replace("{{java}}", java);
    }

    private static String readTemplateResource() {
        java.io.InputStream in =
                YamlCommentScannerTest.class.getResourceAsStream("/xac.yaml.template");
        if (in == null) {
            throw new AssertionError("xac.yaml.template not on the test classpath");
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing useful to do while closing a classpath resource
            }
        }
    }

    private static String joinLines(List<String> lines) {
        if (lines == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }
}
