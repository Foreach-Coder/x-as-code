package io.xac.systemd.tui.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件在某一刻的指纹：存在性、修改时间、字节数与内容 SHA-256。
 *
 * <p>编辑器打开时记一份，保存前再取一份；两份对不上就说明有人在我们背后动过这个文件，
 * UI 据此给出的提示是 "文件已被外部修改"，而不是直接覆盖。mtime 与 size 可能被
 * 刻意或无意的操作掩盖（例如同长度改写后回填时间戳），所以内容摘要也参与比较。</p>
 */
public final class FileStamp {

    private final boolean exists;
    private final long mtimeMillis;
    private final long size;
    private final String sha256;

    private FileStamp(boolean exists, long mtimeMillis, long size, String sha256) {
        this.exists = exists;
        this.mtimeMillis = mtimeMillis;
        this.size = size;
        this.sha256 = sha256;
    }

    /**
     * 取一次指纹。文件不存在不算错误——新建的包在第一次保存前就是这个状态。
     *
     * @throws IOException 路径存在但不可读，或者是一个目录
     */
    public static FileStamp of(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!Files.exists(path)) {
            return new FileStamp(false, 0L, 0L, "");
        }
        if (Files.isDirectory(path)) {
            throw new IOException(path + " is a directory");
        }
        FileTime time = Files.getLastModifiedTime(path);
        long size = Files.size(path);
        return new FileStamp(true, time.toMillis(), size, sha256(Files.readAllBytes(path)));
    }

    public boolean exists() {
        return exists;
    }

    public long mtimeMillis() {
        return mtimeMillis;
    }

    public long size() {
        return size;
    }

    /**
     * 内容摘要的小写十六进制形式；文件不存在时为空串。
     */
    public String sha256() {
        return sha256;
    }

    /**
     * 两个指纹是否描述同一个状态。两边都不存在时视为相同。
     */
    public boolean matches(FileStamp now) {
        if (now == null) {
            return false;
        }
        if (exists != now.exists) {
            return false;
        }
        if (!exists) {
            return true;
        }
        return mtimeMillis == now.mtimeMillis
                && size == now.size
                && sha256.equals(now.sha256);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FileStamp)) {
            return false;
        }
        FileStamp that = (FileStamp) other;
        return exists == that.exists
                && mtimeMillis == that.mtimeMillis
                && size == that.size
                && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        int result = exists ? 1 : 0;
        result = 31 * result + (int) (mtimeMillis ^ (mtimeMillis >>> 32));
        result = 31 * result + (int) (size ^ (size >>> 32));
        result = 31 * result + sha256.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return exists
                ? "FileStamp[mtime=" + mtimeMillis + ", size=" + size + ", sha256=" + sha256 + "]"
                : "FileStamp[missing]";
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
