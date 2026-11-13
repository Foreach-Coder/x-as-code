package io.xac.systemd.tui;

import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.systemd.SystemdTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/**
 * 在真机（容器）里把 TUI 的几帧导出成 JSON，交给 {@code e2e/screens.py} 渲染 PNG。
 * <p>
 * 只在人工审图时用，不参与构建：{@code java -cp classes:test-classes:deps} 调用。
 * 走真实的 {@link ServicePackage}（路径、journald 都来自目标机），显示层换成
 * {@link RecordingDisplay}，把 Display 实际收到的字符与语义角色原样导出。
 */
public final class TuiScreenDump {

    private TuiScreenDump() {
    }

    public static void main(String[] args) throws Exception {
        Path pkgDir = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        Files.createDirectories(outDir);
        dump(pkgDir, outDir, "main");
        dump(pkgDir, outDir, "apply", KeyEvent.character('a'));
        dump(pkgDir, outDir, "destroy", KeyEvent.character('d'));
        dump(pkgDir, outDir, "edit", KeyEvent.character('e'));
        System.out.println("DUMPED " + outDir);
    }

    private static void dump(final Path pkgDir, Path outDir, String name, KeyEvent... keys)
            throws Exception {
        final ServicePackage pkg = ServicePackage.open(pkgDir, new ProcessRunner(),
                new RealEnvironmentProbe(new ProcessRunner()), SystemdTarget.defaults());
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(pkg, display, new ScriptedKeySource(keys),
                Clock.systemUTC(), new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        return pkg.logs(lines, follow);
                    }
                });
        app.tick();
        // journald 日志是异步泵进缓冲的，等一拍再画，截图里才有日志区内容。
        Thread.sleep(600L);
        app.tick();
        if (keys.length > 0) {
            app.tick();
            Thread.sleep(100L);
            app.tick();
        }
        write(outDir.resolve(name + ".json"), name, display);
        app.close();
    }

    private static void write(Path file, String name, RecordingDisplay display)
            throws IOException {
        Canvas canvas = display.last();
        StringBuilder json = new StringBuilder(8192);
        json.append("{\"name\":\"").append(name).append("\",\"cols\":80,\"rows\":24,\"cells\":[");
        boolean first = true;
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) {
                char ch = canvas.charAt(x, y);
                Role role = canvas.roleAt(x, y);
                if ((ch == ' ' && role == Role.DEFAULT) || ch == Canvas.PLACEHOLDER) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"x\":").append(x).append(",\"y\":").append(y)
                        .append(",\"ch\":").append(quote(String.valueOf(ch)))
                        .append(",\"role\":\"").append(role.name()).append("\"}");
            }
        }
        json.append("]}");
        Files.write(file, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
