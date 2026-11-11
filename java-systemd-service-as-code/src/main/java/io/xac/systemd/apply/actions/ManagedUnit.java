package io.xac.systemd.apply.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import io.xac.systemd.systemd.UnitRenderer;

/** 识别 unit / env 文件是否由 xac 生成；首个物理行是管理头。 */
public final class ManagedUnit {

    public static boolean hasHeader(Path unitPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(unitPath, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first != null && !first.isEmpty() && first.charAt(0) == '\uFEFF') {
                first = first.substring(1);
            }
            return UnitRenderer.HEADER.equals(first);
        }
    }

    /** 只有「普通文件 + 首行管理头」才算 xac 所有；软链接、目录、读失败一律不算。 */
    public static boolean isManagedRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return hasHeader(path);
        } catch (IOException e) {
            return false;
        }
    }

    /** 路径上已存在东西，但不是 xac 写的普通文件。 */
    public static boolean existsAndIsForeign(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !isManagedRegularFile(path);
    }

    private ManagedUnit() {
    }
}
