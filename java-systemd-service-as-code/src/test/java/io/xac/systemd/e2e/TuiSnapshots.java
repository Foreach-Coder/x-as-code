package io.xac.systemd.e2e;

import io.xac.systemd.tui.Role;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 视觉快照：把 PTY 里抓到的最后一帧（字符 + 语义角色）与仓库里的快照比对。
 *
 * <p>只做结构门禁，不做像素比对——像素渲染在 {@code e2e/screens.py}，产物留给人看。
 * 时间戳、PID、容器 ID、日志正文这类每次运行都变的文本在比对前被替换成占位符。</p>
 *
 * <p>{@code -Dxac.updateSnapshots=true} 时把当前帧写成新快照（只在人工确认视觉变化后使用）。</p>
 */
final class TuiSnapshots {

    private static final String PROPERTY = "xac.updateSnapshots";
    private static final Path SNAPSHOT_DIR =
            Paths.get("src", "test", "resources", "tui-snapshots");

    private TuiSnapshots() {
    }

    static void verify(String name, AnsiScreen.Screen screen, Path pngDir) throws IOException {
        List<String> actual = structural(trimTrailingEmpty(normalize(screen.lines())));
        if (Boolean.getBoolean(PROPERTY)) {
            writeSnapshot(name, actual);
        } else {
            compare(name, actual);
        }
        writeCells(name, screen, pngDir);
    }

    /**
     * 日志区内容取决于 journald 里刚好有多少行，不稳定；快照只锁结构：
     * 标题、状态行、日志区表头，以及底部的通知/键栏。日志本身由其它断言覆盖。
     */
    private static List<String> structural(List<String> lines) {
        // 日志区行数随 journald 波动，且覆盖层大小不同会遮住表头，不能靠行号定位。
        // 统一按内容剔除：表头行与已被掩码成 "│ <log>" 的行都不进快照。
        List<String> out = new ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith("├ logs")) {
                continue;
            }
            String trimmed = line.trim();
            // 空行也随日志条数变化（日志区没内容时就是空的），一并剔掉。
            if (trimmed.isEmpty() || trimmed.startsWith("│ <log>") || trimmed.equals("<log>")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }
    private static List<String> trimTrailingEmpty(List<String> lines) {
        List<String> out = new ArrayList<String>(lines);
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static void compare(String name, List<String> actual) throws IOException {
        List<String> expected = readSnapshot(name);
        if (expected.equals(actual)) {
            return;
        }
        fail("tui screen [" + name + "] differs from src/test/resources/tui-snapshots/"
                + name + ".txt\n" + diff(expected, actual)
                + "\nrun with -D" + PROPERTY + "=true after confirming the visual change");
    }

    private static String diff(List<String> expected, List<String> actual) {
        int rows = Math.max(expected.size(), actual.size());
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int y = 0; y < rows && shown < 12; y++) {
            String e = y < expected.size() ? expected.get(y) : "<missing>";
            String a = y < actual.size() ? actual.get(y) : "<missing>";
            if (!e.equals(a)) {
                shown++;
                out.append(String.format("%2d  - %s%n", y, e));
                out.append(String.format("%2d  + %s%n", y, a));
            }
        }
        if (shown == 0) {
            out.append("(line lists differ only in length)\n");
        }
        return out.toString();
    }

    static List<String> normalize(List<String> lines) {
        List<String> out = new ArrayList<String>(lines.size());
        for (String line : lines) {
            String value = line;
            int width = line.length();
            if (value.matches("^│ [A-Z][a-z]{2} [ 0-9]\\d \\d{2}:\\d{2}:\\d{2} .*")) {
                // 日志正文每次都不同（时间、PID、消息长度），整段替换成 <log>，
                // 但保留左右边框，行宽与原帧一致。
                StringBuilder masked = new StringBuilder(value.length());
                masked.append("│ <log>");
                while (masked.length() < value.length() - 1) {
                    masked.append(' ');
                }
                if (value.length() > 0) {
                    masked.append('│');
                }
                value = masked.toString();
            }
            value = value.replaceAll("\\bpid \\d+", "pid <pid>")
                    .replaceAll("\\b[0-9a-f]{12}\\b", "<id>");
            char last = value.charAt(value.length() - 1);
            if (last == '│' || last == '┤' || last == '┐' || last == '┘') {
                // 掩码会改变中间文本的长度（pid 位数不同），右边框必须留在原列，
                // 否则同一帧在不同环境下快照不等。
                String body = value.substring(0, value.length() - 1).replaceAll(" +$", "");
                StringBuilder padded = new StringBuilder(body);
                while (padded.length() < width - 1) {
                    padded.append(' ');
                }
                value = padded.append(last).toString();
            } else {
                value = value.replaceAll("\\s+$", "");
            }
            out.add(value);
        }
        return out;
    }

    private static List<String> readSnapshot(String name) throws IOException {
        InputStream in = TuiSnapshots.class.getResourceAsStream("/tui-snapshots/" + name + ".txt");
        if (in == null) {
            fail("missing snapshot src/test/resources/tui-snapshots/" + name + ".txt"
                    + " (run with -D" + PROPERTY + "=true to create it)");
            return null;
        }
        try {
            byte[] bytes = readAll(in);
            String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            List<String> out = new ArrayList<String>();
            for (String line : text.split("\n", -1)) {
                out.add(line);
            }
            while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                out.remove(out.size() - 1);
            }
            return out;
        } finally {
            in.close();
        }
    }

    private static void writeSnapshot(String name, List<String> lines) throws IOException {
        Files.createDirectories(SNAPSHOT_DIR);
        Path file = SNAPSHOT_DIR.resolve(name + ".txt");
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("updated snapshot: " + file);
    }

    private static void writeCells(String name, AnsiScreen.Screen screen, Path pngDir)
            throws IOException {
        Files.createDirectories(pngDir);
        Path file = pngDir.resolve(name + ".json");
        try (OutputStream stream = Files.newOutputStream(file);
             PrintWriter writer = new PrintWriter(
                     new java.io.OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            writer.print("{\"name\":");
            writer.print(quote(name));
            writer.print(",\"cols\":");
            writer.print(screen.cols());
            writer.print(",\"rows\":");
            writer.print(screen.rows());
            writer.print(",\"cells\":[");
            boolean first = true;
            for (int y = 0; y < screen.rows(); y++) {
                for (int x = 0; x < screen.cols(); x++) {
                    char ch = screen.charAt(x, y);
                    Role role = screen.roleAt(x, y);
                    if (ch == ' ' && role == Role.DEFAULT) {
                        continue;
                    }
                    if (!first) {
                        writer.print(',');
                    }
                    first = false;
                    writer.print("{\"x\":");
                    writer.print(x);
                    writer.print(",\"y\":");
                    writer.print(y);
                    writer.print(",\"ch\":");
                    writer.print(quote(String.valueOf(ch)));
                    writer.print(",\"role\":");
                    writer.print(quote(role.name()));
                    writer.print('}');
                }
            }
            writer.print("]}");
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
