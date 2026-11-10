package io.xac.systemd.spec;

import java.io.IOException;
import java.nio.file.Files;
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
        // 目标按真实父路径解析（最后一段走 lstat）；其余四个路径也按真实路径解析，
        // 再同时比较词法形式与解析形式——解析失败时词法相等同样必须拒绝。
        Path source = link.source.toAbsolutePath().normalize();
        Path sourceReal = resolveReal(link.source);
        Path target = resolveTarget(link.target);
        Path targetLexical = link.target.toAbsolutePath().normalize();
        Path unit = unitDir.toAbsolutePath().normalize();
        Path unitReal = resolveReal(unitDir);
        Path env = envDir.toAbsolutePath().normalize();
        Path envReal = resolveReal(envDir);
        Path pkg = packageDir.toAbsolutePath().normalize();
        Path pkgReal = resolveReal(packageDir);

        for (Path protectedDir : absoluteProtected()) {
            if (isRoot(protectedDir)) {
                if (target.equals(protectedDir) || targetLexical.equals(protectedDir)) {
                    return "目标是被保护的系统目录: " + targetLexical;
                }
            } else if (target.equals(protectedDir) || target.startsWith(protectedDir)
                    || targetLexical.equals(protectedDir) || targetLexical.startsWith(protectedDir)) {
                return "目标是被保护的系统目录: " + targetLexical;
            }
        }
        if (target.equals(source) || target.equals(sourceReal)
                || targetLexical.equals(source) || targetLexical.equals(sourceReal)) {
            return "目标不能与源相同: " + targetLexical;
        }
        if (isAncestorOrSameAny(target, targetLexical, unit, unitReal)
                || isAncestorOrSameAny(target, targetLexical, env, envReal)
                || isAncestorOrSameAny(target, targetLexical, pkg, pkgReal)) {
            return "目标不能是 unit 目录、env 目录或包目录本身或其上级: " + targetLexical;
        }
        if (target.startsWith(unit) || target.startsWith(unitReal)
                || targetLexical.startsWith(unit) || targetLexical.startsWith(unitReal)) {
            return "目标不能位于 unit 目录下: " + targetLexical;
        }
        if (source.startsWith(target) || source.startsWith(targetLexical)
                || sourceReal.startsWith(target) || sourceReal.startsWith(targetLexical)) {
            return "目标不能是源目录的上级目录: " + targetLexical;
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

    /** 用于 source / unitDir / envDir / packageDir：尽量解析成真实路径，失败时退回词法形式。 */
    static Path resolveReal(Path path) {
        Path resolved = resolveExisting(path.toAbsolutePath());
        return resolved == null ? path.toAbsolutePath().normalize() : resolved;
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

    /** 目标（词法或真实形式）是 dir（词法或真实形式）本身或其上级。 */
    private static boolean isAncestorOrSameAny(Path t1, Path t2, Path d1, Path d2) {
        return d1.startsWith(t1) || d1.startsWith(t2)
                || d2.startsWith(t1) || d2.startsWith(t2);
    }

    private PathLinkGuard() {
    }
}
