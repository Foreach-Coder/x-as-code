package io.xac.systemd.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * path-links 目标的护栏：校验层（exit 2，改动前）与动作层（ActionFailure）共用。
 * 只允许把链接建到不会破坏系统或本工具自身状态的目录上。
 */
public final class PathLinkGuard {

    /** 明确保护的系统目录；/opt 不在其中（允许作为链接目标）。 */
    private static final List<Path> PROTECTED = Arrays.asList(
            Paths.get("/"),
            Paths.get("/etc"),
            Paths.get("/etc/systemd"),
            Paths.get("/etc/systemd/system"),
            Paths.get("/etc/xac"),
            Paths.get("/usr"),
            Paths.get("/bin"),
            Paths.get("/sbin"),
            Paths.get("/lib"),
            Paths.get("/lib32"),
            Paths.get("/lib64"),
            Paths.get("/boot"),
            Paths.get("/var"),
            Paths.get("/root"),
            Paths.get("/home"),
            Paths.get("/proc"),
            Paths.get("/sys"),
            Paths.get("/dev"),
            Paths.get("/run"));

    /** 返回一行中文错误；合法时返回 null。动作层使用：结构 + 源目录存在。 */
    public static String check(PathLink link, Path unitDir, Path envDir, Path packageDir) {
        String shape = checkShape(link, unitDir, envDir, packageDir);
        if (shape != null) {
            return shape;
        }
        return checkSource(link);
    }

    /**
     * 只做结构检查，不要求源目录已经存在。
     * 校验层（status / logs / destroy / apply 共用）用它，保证危险目标在改动前就被拒绝。
     */
    public static String checkShape(PathLink link, Path unitDir, Path envDir, Path packageDir) {
        Path source = link.source.toAbsolutePath().normalize();
        Path target = resolveTarget(link.target);
        Path unit = unitDir.toAbsolutePath().normalize();

        for (Path protectedDir : absoluteProtected()) {
            if (isRoot(protectedDir)) {
                if (target.equals(protectedDir)) {
                    return "目标是被保护的系统目录: " + target;
                }
            } else if (target.equals(protectedDir) || target.startsWith(protectedDir)) {
                return "目标是被保护的系统目录: " + target;
            }
        }
        if (target.equals(source)) {
            return "目标不能与源相同: " + target;
        }
        if (isAncestorOrSame(target, unit)
                || isAncestorOrSame(target, envDir)
                || isAncestorOrSame(target, packageDir)) {
            return "目标不能是 unit 目录、env 目录或包目录本身或其上级: " + target;
        }
        if (target.startsWith(unit)) {
            return "目标不能位于 unit 目录下: " + target;
        }
        return null;
    }

    /**
     * 按真实路径解析目标：从最深的已存在祖先开始 toRealPath，再拼回剩余段。
     * 这样 /opt/link/etc（link -> /）会被识别为 /etc；最后一段保留 lstat 语义，
     * 因为移动一个符号链接只是移动链接本身，不会动到它的指向。
     */
    static Path resolveTarget(Path target) {
        Path absolute = target.toAbsolutePath();
        Path fileName = absolute.getFileName();
        boolean dotSegment = fileName == null
                || ".".equals(fileName.toString())
                || "..".equals(fileName.toString());
        if (dotSegment) {
            Path resolved = resolveExisting(absolute);
            return resolved == null ? absolute.normalize() : resolved;
        }
        Path parent = resolveExisting(absolute.getParent());
        if (parent == null) {
            return absolute.normalize();
        }
        return parent.resolve(fileName.toString());
    }

    private static Path resolveExisting(Path path) {
        if (path == null) {
            return null;
        }
        if (Files.exists(path)) {
            try {
                return path.toRealPath();
            } catch (IOException ignored) {
                // 无法解析时退化为逐段拼接。
            }
        }
        Path parent = resolveExisting(path.getParent());
        Path name = path.getFileName();
        if (parent == null || name == null) {
            return path.toAbsolutePath().normalize();
        }
        if ("..".equals(name.toString())) {
            Path up = parent.getParent();
            return up == null ? parent : up;
        }
        if (".".equals(name.toString())) {
            return parent;
        }
        return parent.resolve(name.toString());
    }

    private static boolean isRoot(Path path) {
        Path root = path.getRoot();
        if (root == null) {
            return false;
        }
        Path rest = root.relativize(path);
        return rest.toString().isEmpty() || ".".equals(rest.toString());
    }

    /** 只在 apply 前使用：源目录不存在会建出悬空链接。 */
    public static String checkSource(PathLink link) {
        Path source = link.source.toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            return "源目录不存在: " + source;
        }
        return null;
    }

    private static List<Path> absoluteProtected() {
        List<Path> result = new ArrayList<Path>(PROTECTED.size());
        for (Path path : PROTECTED) {
            result.add(path.toAbsolutePath().normalize());
        }
        return result;
    }

    private static boolean isAncestorOrSame(Path target, Path dir) {
        return dir.toAbsolutePath().normalize().startsWith(target);
    }
    private PathLinkGuard() {
    }
}
