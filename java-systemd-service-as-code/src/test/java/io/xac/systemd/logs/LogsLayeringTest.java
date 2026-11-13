package io.xac.systemd.logs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 分层护栏：{@code logs} 是基础层，{@code core} 依赖它，反过来不行。
 *
 * <p>这条约束只体现在 import 上，编译期不会报错，所以用一个源码扫描测试把它钉住：
 * 一旦有人在 {@code logs} 里 import 了 {@code core}（包环重新出现），这里就红。
 */
class LogsLayeringTest {

    @Test
    void logsDoesNotImportCore() throws IOException {
        List<String> offenders = new ArrayList<String>();
        DirectoryStream<Path> sources = Files.newDirectoryStream(logsSources(), "*.java");
        try {
            for (Path source : sources) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("import io.xac.systemd.core.")) {
                        offenders.add(source.getFileName() + ":" + (i + 1) + " "
                                + lines.get(i).trim());
                    }
                }
            }
        } finally {
            sources.close();
        }

        assertTrue(offenders.isEmpty(),
                "logs must not import core (core depends on logs, not the other way): "
                        + offenders);
    }

    /**
     * 定位 logs 主源码目录：surefire 的 cwd 是模块目录，IDE 里可能直接是仓库根。
     */
    private static Path logsSources() {
        Path moduleRelative = Paths.get("src", "main", "java", "io", "xac", "systemd", "logs");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path rootRelative = Paths.get("java-systemd-service-as-code", "src", "main", "java",
                "io", "xac", "systemd", "logs");
        if (Files.isDirectory(rootRelative)) {
            return rootRelative;
        }
        throw new AssertionError("cannot locate logs sources from "
                + Paths.get("").toAbsolutePath());
    }
}
