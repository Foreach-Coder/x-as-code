package io.xac.systemd.spec;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathLink {

    public final String raw;
    public final Path source;
    public final Path target;

    private PathLink(String raw, Path source, Path target) {
        this.raw = raw;
        this.source = source;
        this.target = target;
    }

    /** 按最后一个冒号切分，source 相对包目录解析；不合法时返回 null。 */
    public static PathLink parse(String raw, Path packageDir) {
        if (raw == null) {
            return null;
        }
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            return null;
        }
        String source = raw.substring(0, colon);
        String target = raw.substring(colon + 1);
        if (target.isEmpty() || !target.startsWith("/")) {
            return null;
        }
        return new PathLink(raw, packageDir.resolve(source).normalize(), Paths.get(target));
    }

    /** 没有包目录上下文时，source 相对当前工作目录解析。 */
    public static PathLink parse(String raw) {
        return parse(raw, Paths.get("").toAbsolutePath().normalize());
    }

    /** 供测试与内部逻辑直接构造，绕过字符串解析。 */
    static PathLink of(Path source, Path target) {
        return new PathLink(source + ":" + target, source, target);
    }
}
