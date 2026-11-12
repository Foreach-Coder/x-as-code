package io.xac.systemd.spec;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpecWriterTest {

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

    private String renderWithEnvValue(String key, String value) {
        ServiceSpec spec = builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map(key, value))
                .build();
        return SpecWriter.render(spec);
    }

    private static String lineOf(String yaml, String prefix) {
        for (String line : yaml.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("no line starting with [" + prefix + "] in:\n" + yaml);
    }

    private static boolean isPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            List<String> names = entries
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            Collections.sort(names);
            return names;
        }
    }

    // ------------------------------------------------------------ fixed shape

    @Test
    void rendersMinimalSpecWithFixedShape() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());

        assertEquals(String.join("\n",
                "name: demo",
                "",
                "artifact:",
                "  jar: app.jar",
                "",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts: []",
                "",
                "lifecycle:",
                "  auto-start: true",
                "  restart: on-failure",
                "  restart-sec: 5",
                "  timeout-stop-sec: 60",
                ""), yaml);
    }

    @Test
    void writesSectionsInFixedOrder() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m"))
                .env(map("A", "1"))
                .properties(map("server.port", "8080"))
                .workdir("run")
                .pathLinks(Arrays.asList("./logs:/applog/demo"))
                .logFile("/var/log/demo/app.log")
                .build());

        String[] markers = {
                "name: demo",
                "\ndescription: demo service",
                "\nartifact:\n",
                "\nruntime:\n",
                "\nenv:\n",
                "\nproperties:\n",
                "\nworkdir: ",
                "\npath-links:\n",
                "\nlogs:\n",
                "\nlifecycle:\n"
        };
        int previous = -1;
        for (String marker : markers) {
            int index = yaml.indexOf(marker);
            assertTrue(index > previous, "[" + marker + "] out of order in:\n" + yaml);
            previous = index;
        }
        assertTrue(yaml.startsWith("name: demo\n"), yaml);
    }

    @Test
    void writesSingleTrailingLfAndNoCr() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m", "-Dx=a b"))
                .build());

        assertTrue(yaml.endsWith("\n"), yaml);
        assertFalse(yaml.endsWith("\n\n"), yaml);
        assertFalse(yaml.contains("\r"), yaml);
        assertFalse(yaml.startsWith("\uFEFF"), yaml);
    }

    @Test
    void writesNoComments() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .description("demo service")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());

        assertFalse(yaml.contains("#"), yaml);
    }

    @Test
    void isDeterministicAndIndependentOfInputMapOrder() {
        ServiceSpec first = builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map("B", "2", "A", "1", "C", "3"))
                .properties(map("z", "9", "a", "1"))
                .build();
        ServiceSpec second = builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map("A", "1", "C", "3", "B", "2"))
                .properties(map("a", "1", "z", "9"))
                .build();

        String yaml = SpecWriter.render(first);
        assertEquals(yaml, SpecWriter.render(first));
        assertEquals(yaml, SpecWriter.render(second));
    }

    @Test
    void sortsEnvAndPropertiesKeysButKeepsListOrder() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Zlast", "-Afirst"))
                .env(map("ZED", "z", "ALPHA", "a", "MIKE", "m"))
                .properties(map("z.key", "z", "a.key", "a"))
                .pathLinks(Arrays.asList("../z:/opt/z", "../a:/opt/a"))
                .build());

        assertTrue(yaml.contains("\n  ALPHA: a\n  MIKE: m\n  ZED: z\n"), yaml);
        assertTrue(yaml.contains("\n  a.key: a\n  z.key: z\n"), yaml);
        assertTrue(yaml.indexOf("- -Zlast") < yaml.indexOf("- -Afirst"), yaml);
        assertTrue(yaml.indexOf("../z:/opt/z") < yaml.indexOf("../a:/opt/a"), yaml);
    }

    @Test
    void writesOptsAsItemsAndEmptyOptsAsFlowSequence() {
        String items = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m", "-Dmessage=hello world", "-Dflag=a: b"))
                .build());
        assertTrue(items.contains("\n  opts:\n    - -Xmx512m\n    - -Dmessage=hello world\n"
                + "    - \"-Dflag=a: b\"\n"), items);

        String empty = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());
        assertTrue(empty.contains("\n  opts: []\n"), empty);
    }

    @Test
    void omitsEmptyOptionalSections() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .description("")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());

        assertFalse(yaml.contains("description:"), yaml);
        assertFalse(yaml.contains("env:"), yaml);
        assertFalse(yaml.contains("properties:"), yaml);
        assertFalse(yaml.contains("workdir:"), yaml);
        assertFalse(yaml.contains("path-links:"), yaml);
        assertFalse(yaml.contains("logs:"), yaml);
        // lifecycle is always written, with the documented defaults.
        assertTrue(yaml.contains("\nlifecycle:\n"), yaml);
    }

    @Test
    void writesPathsRelativeToPackageDirWithForwardSlashes() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("lib/app.jar")
                .java("/usr/bin/java")
                .workdir("run")
                .build());

        assertTrue(yaml.contains("\n  jar: lib/app.jar\n"), yaml);
        assertTrue(yaml.contains("\nworkdir: run\n"), yaml);
        assertFalse(yaml.contains("\\"), yaml);
    }

    @Test
    void writesExplicitLifecycleValues() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .lifecycle(new Lifecycle(Boolean.FALSE, "always", 30, 90))
                .build());

        assertTrue(yaml.contains("\nlifecycle:\n  auto-start: false\n  restart: always\n"
                + "  restart-sec: 30\n  timeout-stop-sec: 90\n"), yaml);
    }

    @Test
    void keepsRestartNoUnquotedBecauseYaml12ReadsItAsText() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .lifecycle(new Lifecycle(null, "no", null, null))
                .build());

        assertTrue(yaml.contains("\n  restart: no\n"), yaml);
    }

    // --------------------------------------------------------------- quoting

    @Test
    void quotesValuesThatWouldOtherwiseChangeMeaning() {
        String[][] cases = {
                {"a: b", "\"a: b\""},
                {"a:b", "\"a:b\""},
                {"#x", "\"#x\""},
                {"x#y", "\"x#y\""},
                {"a:", "\"a:\""},
                {"%percent", "\"%percent\""},
                {"@at", "\"@at\""},
                {" leading", "\" leading\""},
                {"trailing ", "\"trailing \""},
                {"  ", "\"  \""},
                {"", "\"\""},
                {"true", "\"true\""},
                {"True", "\"True\""},
                {"FALSE", "\"FALSE\""},
                {"null", "\"null\""},
                {"~", "\"~\""},
                {"8080", "\"8080\""},
                {"-42", "\"-42\""},
                {"1.5", "\"1.5\""},
                {"1e3", "\"1e3\""},
                {"0x1f", "\"0x1f\""},
                {"0755", "\"0755\""},
                {"1_000", "\"1_000\""},
                {"12:30", "\"12:30\""},
                {"2024-01-01", "\"2024-01-01\""},
                {"${HOME}", "\"${HOME}\""},
                {"-", "\"-\""},
                {"?", "\"?\""},
                {"[INFO]", "\"[INFO]\""},
                {"*star", "\"*star\""},
                {"&anchor", "\"&anchor\""},
                {"!tag", "\"!tag\""},
                {"back\\slash", "back\\slash"},
                {"\"quote\"", "\"\\\"quote\\\"\""},
                // plain scalars stay plain
                {"-Xmx512m", "-Xmx512m"},
                {"hello world", "hello world"},
                {"on-failure", "on-failure"},
                {"a/b/c.jar", "a/b/c.jar"},
                {"no", "no"},
                {"yes", "yes"},
                {"中文值", "中文值"}
        };

        for (String[] c : cases) {
            String yaml = renderWithEnvValue("K", c[0]);
            assertEquals("  K: " + c[1], lineOf(yaml, "  K: "),
                    "unexpected rendering for [" + c[0] + "]");
        }
    }

    @Test
    void quotesKeysThatWouldOtherwiseChangeType() {
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .properties(map("true", "1", "a:b", "2", "server.port", "8080"))
                .build());

        assertTrue(yaml.contains("\n  \"a:b\": \"2\"\n"), yaml);
        assertTrue(yaml.contains("\n  server.port: \"8080\"\n"), yaml);
        assertTrue(yaml.contains("\n  \"true\": \"1\"\n"), yaml);
    }

    @Test
    void escapesControlCharactersInsteadOfUsingBlockScalars() {
        String yaml = renderWithEnvValue("K", "a\nb\tc");
        assertEquals("  K: \"a\\nb\\tc\"", lineOf(yaml, "  K: "));
        assertFalse(yaml.contains("|"), yaml);
        assertFalse(yaml.contains(">"), yaml);

        SpecLoader.Result loaded = load(SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map("K", "a\nb\tc"))
                .build()));
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertEquals("a\nb\tc", loaded.spec.env.get("K"));
    }

    @Test
    void escapesUnicodeLineBreaksSoTheySurviveARoundTrip() {
        String value = "a\u0085b\u2028c\u2029d";
        String yaml = renderWithEnvValue("K", value);
        assertEquals("  K: \"a\\u0085b\\u2028c\\u2029d\"", lineOf(yaml, "  K: "));

        SpecLoader.Result loaded = load(SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .env(map("K", value))
                .build()));
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertEquals(value, loaded.spec.env.get("K"));
    }

    // ------------------------------------------------------------- round trip

    @Test
    void roundTripsThroughSpecLoader() throws IOException {
        Path log = pkg.resolve("app.log").toAbsolutePath();
        ServiceSpec original = builder()
                .name("demo")
                .description("演示服务：带冒号 #和井号")
                .jar("lib/app.jar")
                .java("/usr/bin/java")
                .opts(Arrays.asList("-Xmx512m", "-Dmessage=hello world", "-Dflag=a: b"))
                .env(map(
                        "GREETING", "hello world",
                        "TRICKY", "a: b",
                        "HASH", "#x",
                        "QUOTE", "\"quote\"",
                        "ENV_REF", "${HOME}",
                        "EMPTY", "",
                        "NUMBER", "8080",
                        "BOOL", "true",
                        "CHINESE", "中文值"))
                .properties(map("server.port", "8080", "app.name", "xac demo"))
                .workdir("run")
                .pathLinks(Arrays.asList("./logs:/applog/demo", "./conf:/opt/demo/conf"))
                .logFile(log.toString().replace("\\", "/"))
                .build();

        String first = SpecWriter.render(original);
        SpecLoader.Result loaded = load(first);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());

        String second = SpecWriter.render(loaded.spec);
        assertEquals(first, second, "render -> load -> render must be a fixed point");

        assertTrue(ValidationRunner.run(loaded.spec).isEmpty(),
                ValidationRunner.run(loaded.spec).toString());
    }

    @Test
    void roundTripKeepsEmptyEnvironmentAndOptsStable() {
        String first = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());
        SpecLoader.Result loaded = load(first);
        assertTrue(loaded.violations.isEmpty(), loaded.violations.toString());
        assertEquals(first, SpecWriter.render(loaded.spec));
    }

    private SpecLoader.Result load(String yaml) {
        try {
            Files.write(pkg.resolve("xac.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return SpecLoader.load(pkg);
    }

    // ------------------------------------------------------------------ save

    @Test
    void saveWritesContentAndLeavesNoTemporaryOrBackupFiles() throws IOException {
        Path target = pkg.resolve("xac.yaml");
        Files.write(target, "old: content\n".getBytes(StandardCharsets.UTF_8));

        String yaml = SpecWriter.render(builder()
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());
        SpecWriter.save(target, yaml);

        assertEquals(yaml, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList("xac.yaml"), namesIn(pkg));
    }

    @Test
    void saveCreatesMissingFileWithMode0644() throws IOException {
        Path target = pkg.resolve("xac.yaml");
        SpecWriter.save(target, "name: demo\n");

        assertTrue(Files.isRegularFile(target), target.toString());
        assertEquals("name: demo\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));

        assumeTrue(isPosix(), "POSIX permissions only");
        assertEquals("rw-r--r--", posixMode(target));
    }

    @Test
    void saveResetsPermissionsOfExistingFileTo0644() throws IOException {
        assumeTrue(isPosix(), "POSIX permissions only");
        Path target = pkg.resolve("xac.yaml");
        Files.write(target, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));

        SpecWriter.save(target, "new\n");

        assertEquals("rw-r--r--", posixMode(target));
    }

    @Test
    void saveReplacesFileByRenameNotInPlaceWrite() throws IOException {
        assumeTrue(isPosix(), "inode identity is POSIX-specific");
        Path target = pkg.resolve("xac.yaml");
        Files.write(target, "old\n".getBytes(StandardCharsets.UTF_8));
        Object before = Files.getAttribute(target, "unix:ino");

        SpecWriter.save(target, "new\n");

        Object after = Files.getAttribute(target, "unix:ino");
        assertNotEquals(before, after, "atomic move should install a new inode");
        assertEquals("new\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    void saveRejectsMissingParentDirectory() {
        Path target = pkg.resolve("missing").resolve("xac.yaml");
        IOException error = assertThrows(IOException.class,
                () -> SpecWriter.save(target, "name: demo\n"));
        assertTrue(error.getMessage().contains("parent"), error.getMessage());
        assertFalse(Files.exists(target), target.toString());
    }

    @Test
    void saveFailureKeepsExistingTargetAndCleansTempFile() throws IOException {
        // A directory in the target position makes the final move fail; nothing
        // of the pre-existing tree may be destroyed and no temp file may leak.
        Path target = pkg.resolve("xac.yaml");
        Files.createDirectories(target);
        Path child = target.resolve("keep.txt");
        Files.write(child, "keep\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> SpecWriter.save(target, "name: demo\n"));

        assertTrue(Files.isDirectory(target), target.toString());
        assertEquals("keep\n", new String(Files.readAllBytes(child), StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList("xac.yaml"), namesIn(pkg));
    }

    @Test
    void saveWritesUtf8() throws IOException {
        Path target = pkg.resolve("xac.yaml");
        String yaml = SpecWriter.render(builder()
                .name("demo")
                .description("中文描述")
                .jar("app.jar")
                .java("/usr/bin/java")
                .build());

        SpecWriter.save(target, yaml);

        assertEquals(yaml, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue(yaml.contains("description: 中文描述"), yaml);
    }

    private static String posixMode(Path file) throws IOException {
        List<String> bits = new ArrayList<String>();
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                Files.getPosixFilePermissions(file);
        int[] masks = {0400, 0200, 0100, 040, 020, 010, 04, 02, 01};
        char[] symbols = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < masks.length; i++) {
            bits.add(permissions.contains(toPermission(masks[i])) ? String.valueOf(symbols[i]) : "-");
        }
        return String.join("", bits);
    }

    private static java.nio.file.attribute.PosixFilePermission toPermission(int mask) {
        switch (mask) {
            case 0400: return java.nio.file.attribute.PosixFilePermission.OWNER_READ;
            case 0200: return java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
            case 0100: return java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
            case 040: return java.nio.file.attribute.PosixFilePermission.GROUP_READ;
            case 020: return java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
            case 010: return java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
            case 04: return java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
            case 02: return java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
            default: return java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
        }
    }
}
