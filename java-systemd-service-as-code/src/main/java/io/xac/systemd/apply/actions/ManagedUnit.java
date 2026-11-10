package io.xac.systemd.apply.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.systemd.UnitRenderer;

/** 识别 unit 文件是否由 xac 生成；首个物理行是管理头。 */
final class ManagedUnit {

    static boolean hasHeader(Path unitPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(unitPath, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            return UnitRenderer.HEADER.equals(first);
        }
    }

    private ManagedUnit() {
    }
}
