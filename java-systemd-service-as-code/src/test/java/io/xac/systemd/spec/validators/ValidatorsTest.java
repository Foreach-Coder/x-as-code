package io.xac.systemd.spec.validators;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorsTest {

    private static final Path PKG = Paths.get("/opt/demo");

    private static ServiceSpec.Builder base() {
        return ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(asList("-Xmx512m"))
                .lifecycle(new Lifecycle(null, null, null, null));
    }

    private static List<String> asList(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }

    private static List<String> messages(List<Violation> violations) {
        List<String> result = new ArrayList<String>();
        for (Violation v : violations) {
            result.add(v.toString());
        }
        return result;
    }

    @Test
    void acceptsValidSpec() {
        assertTrue(ValidationRunner.run(base().build()).isEmpty());
    }

    @Test
    void rejectsBadName() {
        List<Violation> out = ValidationRunner.run(base().name("../etc/passwd").build());
        assertEquals(1, out.size(), messages(out).toString());
        assertEquals("name: 只能包含字母、数字和 _ . : -，不能以 . 开头，不能包含 /",
                out.get(0).toString());
    }

    @Test
    void rejectsBadEnvKeyAndNewlineValue() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("1BAD", "x");
        env.put("GOOD", "line1\nline2");
        List<Violation> out = ValidationRunner.run(base().env(env).build());
        assertEquals(2, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().startsWith("env.1BAD"));
        assertTrue(out.get(1).toString().startsWith("env.GOOD"));
    }

    @Test
    void rejectsPathLinkWithoutAbsoluteTarget() {
        List<Violation> out = ValidationRunner.run(
                base().pathLinks(asList("cfg:relative/path")).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertTrue(out.get(0).path.startsWith("path-links["), out.get(0).toString());
    }

    @Test
    void rejectsPlaceholderNotInEnv() {
        List<Violation> out = ValidationRunner.run(
                base().opts(asList("-Xmx512m", "-Dpw=${SECRET}")).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().contains("${SECRET}"), out.get(0).toString());
    }

    @Test
    void acceptsPlaceholderFromEnvAndEscapedDollar() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("SECRET", "s");
        List<Violation> out = ValidationRunner.run(base()
                .env(env)
                .opts(asList("-Dpw=${SECRET}", "-Dlit=$${NOT_A_REF}"))
                .build());
        assertTrue(out.isEmpty(), messages(out).toString());
    }

    @Test
    void rejectsNewlineInDescription() {
        List<Violation> out = ValidationRunner.run(
                base().description("第一行\n第二行").build());
        assertEquals(1, out.size(), messages(out).toString());
        assertEquals("description: 不能包含换行", out.get(0).toString());
    }

    @Test
    void rejectsNewlineInOpts() {
        List<Violation> out = ValidationRunner.run(
                base().opts(asList("-Xmx512m", "-Dx=a\rb")).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertEquals("runtime.opts[1]: 不能包含换行", out.get(0).toString());
    }

    @Test
    void rejectsNewlineInProperties() {
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("foo", "a\nb");
        List<Violation> out = ValidationRunner.run(base().properties(properties).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertEquals("properties.foo: 不能包含换行", out.get(0).toString());
    }

    @Test
    void rejectsBadLifecycleValues() {
        List<Violation> out = ValidationRunner.run(base()
                .lifecycle(new Lifecycle(true, "sometimes", 0, -1))
                .build());
        assertEquals(3, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().startsWith("lifecycle.restart"));
    }
}
