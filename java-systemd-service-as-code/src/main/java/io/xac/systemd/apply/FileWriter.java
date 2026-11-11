package io.xac.systemd.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class FileWriter {

    public static void write(Path path, String content, String posixPermissions) throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("目录不存在: " + parent);
        }
        Path tmp = Files.createTempFile(parent, ".xac-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString(posixPermissions);
                Files.setPosixFilePermissions(tmp, perms);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private FileWriter() {
    }
}
