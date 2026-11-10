package io.xac.systemd.systemd;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitRendererTest {

    private static final Path PKG = Paths.get("/opt/demo");
    /**
     * ServiceSpec 内部会把 packageDir 转成绝对路径；这里用同样的转换，
     * 使断言在 POSIX 上得到 "/opt/demo"（与 brief 的 golden string 完全一致），
     * 在 Windows 上得到该平台的实际绝对路径。
     */
    private static final Path PKG_ABS = PKG.toAbsolutePath().normalize();
    private static final SystemdTarget TARGET =
            new SystemdTarget(Paths.get("/etc/systemd/system"), Paths.get("/opt/xac"));

    private static List<String> opts(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }

    /**
     * ExecStart 里的路径参数按 unit 的引号规则处理：反斜杠本身需要引号并转义。
     * POSIX 上返回裸路径（与 brief 的 golden string 逐字节一致）；
     * Windows 上路径含反斜杠，因此带引号——这正是 escape() 的契约。
     */
    private static String pathArg(Path path) {
        String text = path.toString();
        return text.indexOf('\\') >= 0 ? "\"" + text.replace("\\", "\\\\") + "\"" : text;
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    @Test
    void rendersFullUnit() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .description("演示服务")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m", "-XX:+UseSerialGC"))
                .properties(map("foo", "${BAR}"))
                .env(map("BAR", "hello world"))
                .workdir("run")
                .lifecycle(new Lifecycle(true, "on-failure", 5, 20))
                .build();

        String expected = String.join("\n",
                "# Managed by xac. Do not edit.",
                "[Unit]",
                "Description=演示服务",
                "",
                "[Service]",
                "Type=simple",
                "WorkingDirectory=" + PKG_ABS.resolve("run"),
                "SuccessExitStatus=143",
                "EnvironmentFile=" + TARGET.envDir.resolve("demo.env"),
                "SyslogIdentifier=demo",
                "ExecStart=/usr/bin/java -Xmx512m -XX:+UseSerialGC -Dfoo=${BAR}"
                        + " -jar " + pathArg(PKG_ABS.resolve("app.jar")),
                "Restart=on-failure",
                "RestartSec=5",
                "TimeoutStopSec=20",
                "",
                "[Install]",
                "WantedBy=multi-user.target",
                "");

        assertEquals(expected, new UnitRenderer().render(spec, TARGET));
    }

    @Test
    void omitsInstallSectionWhenAutoStartIsFalse() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m"))
                .lifecycle(new Lifecycle(false, null, null, null))
                .build();

        String expected = String.join("\n",
                "# Managed by xac. Do not edit.",
                "[Unit]",
                "",
                "[Service]",
                "Type=simple",
                "WorkingDirectory=" + PKG_ABS,
                "SuccessExitStatus=143",
                "SyslogIdentifier=demo",
                "ExecStart=/usr/bin/java -Xmx512m -jar " + pathArg(PKG_ABS.resolve("app.jar")),
                "Restart=on-failure",
                "RestartSec=5",
                "TimeoutStopSec=60",
                "");

        assertEquals(expected, new UnitRenderer().render(spec, TARGET));
    }

    @Test
    void escapesPercentAndQuotesArgumentsWithSpaces() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Dpct=100%", "-Dsp=a b", "-Dq=a\"b"))
                .lifecycle(new Lifecycle(true, "always", 1, 1))
                .build();

        String unit = new UnitRenderer().render(spec, TARGET);
        String execStart = lineOf(unit, "ExecStart=").substring("ExecStart=".length());
        assertEquals("/usr/bin/java -Dpct=100%% \"-Dsp=a b\" \"-Dq=a\\\"b\" "
                + "-jar " + pathArg(PKG_ABS.resolve("app.jar")), execStart);
        assertEquals("always", lineOf(unit, "Restart=").substring("Restart=".length()));
    }

    @Test
    void rendersEnvFileWithQuotedValues() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts())
                .env(map("BAR", "hello world", "EMPTY", "", "QUOTE", "a\"b"))
                .build();

        assertEquals(String.join("\n",
                "# Managed by xac. Do not edit.",
                "BAR=\"hello world\"",
                "EMPTY=",
                "QUOTE=\"a\\\"b\"",
                ""), new UnitRenderer().renderEnvFile(spec));
    }

    @Test
    void renderingIsDeterministic() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m"))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        UnitRenderer renderer = new UnitRenderer();
        assertEquals(renderer.render(spec, TARGET), renderer.render(spec, TARGET));
        assertEquals(UnitNaming.unitName(spec), "demo.service");
        assertEquals(Paths.get("/opt/xac/demo.env"), UnitNaming.envPath(spec, TARGET));
    }

    private static String lineOf(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("没有以 " + prefix + " 开头的行");
    }
}
