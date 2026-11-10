package io.xac.systemd.spec.validators;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目标护栏必须在动作之前挡住，且不依赖软链接支持，所以在所有平台都运行。 */
class PathLinksValidatorTest {

    @TempDir
    Path pkg;

    private ServiceSpec spec(Path packageDir, String... links) {
        return ServiceSpec.builder(packageDir)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(new ArrayList<String>(Arrays.asList(links)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private static String singleMessage(List<Violation> violations) {
        assertEquals(1, violations.size(), violations.toString());
        Violation violation = violations.get(0);
        assertEquals("path-links[0]", violation.path);
        return violation.message;
    }

    @Test
    void acceptsSafeTarget() throws IOException {
        Files.createDirectories(pkg.resolve("cfg"));

        List<Violation> violations = ValidationRunner.run(
                spec(pkg, "cfg:/opt/xac-test-link"));

        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void rejectsProtectedTarget() throws IOException {
        Files.createDirectories(pkg.resolve("cfg"));

        String message = singleMessage(ValidationRunner.run(spec(pkg, "cfg:/etc")));

        assertTrue(message.contains("被保护的系统目录"), message);
    }

    @Test
    void rejectsTargetEqualToSource() {
        // 以当前目录为包目录，让 source 与 target 的绝对化落在同一盘符（Windows）。
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        String message = singleMessage(ValidationRunner.run(
                spec(cwd, "/tmp/xac-same:/tmp/xac-same")));

        assertTrue(message.contains("目标不能与源相同"), message);
    }

    @Test
    void rejectsMissingSource() {
        String message = singleMessage(ValidationRunner.run(
                spec(pkg, "cfg:/opt/xac-missing-src")));

        assertTrue(message.contains("源目录不存在"), message);
    }

    @Test
    void rejectsTargetThatIsAncestorOfPackageDir() {
        String message = singleMessage(ValidationRunner.run(
                spec(Paths.get("/opt/demo"), "cfg:/opt")));

        assertTrue(message.contains("或其上级"), message);
    }

    @Test
    void rejectsTargetEqualToPackageDir() {
        String message = singleMessage(ValidationRunner.run(
                spec(Paths.get("/opt/demo"), "cfg:/opt/demo")));

        assertTrue(message.contains("或其上级"), message);
    }
}
