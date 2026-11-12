package io.xac.systemd.tui.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * 文件在某一刻的指纹：存在性、修改时间、字节数。
 *
 * <p>编辑器打开时记一份，保存前再取一份；两份对不上就说明有人在我们背后动过这个文件，
 * UI 据此给出的提示是”文件已被外部修改”，而不是直接覆盖。</p>
 */
public final class FileStamp {

    private final boolean exists;
    private final long mtimeMillis;
    private final long size;

    private FileStamp(boolean exists, long mtimeMillis, long size) {
        this.exists = exists;
        this.mtimeMillis = mtimeMillis;
        this.size = size;
    }

    /**
     * 取一次指纹。文件不存在不算错误——新建的包在第一次保存前就是这个状态。
     */
    public static FileStamp of(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!Files.exists(path)) {
            return new FileStamp(false, 0L, 0L);
        }
        FileTime time = Files.getLastModifiedTime(path);
        return new FileStamp(true, time.toMillis(), Files.size(path));
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
        return mtimeMillis == now.mtimeMillis && size == now.size;
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
        return exists == that.exists && mtimeMillis == that.mtimeMillis && size == that.size;
    }

    @Override
    public int hashCode() {
        int result = exists ? 1 : 0;
        result = 31 * result + (int) (mtimeMillis ^ (mtimeMillis >>> 32));
        result = 31 * result + (int) (size ^ (size >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return exists ? "FileStamp[mtime=" + mtimeMillis + ", size=" + size + "]"
                : "FileStamp[missing]";
    }
}
