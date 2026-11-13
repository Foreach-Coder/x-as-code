package io.xac.systemd.e2e;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.async.ResultCallback;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 真 systemd 冒烟测试。
 *
 * <p>用一个 privileged 容器跑真实的 systemd（镜像内已装 JRE），把 xac 命令行和示例服务
 * 放进容器，按顺序验证 apply / status / logs / destroy 的完整生命周期。测试之间共享同一个
 * 服务实例，因此必须按 {@link Order} 顺序执行；容器也只启动一次。</p>
 *
 * <p>只由 failsafe 在 {@code -Pe2e} 下执行，{@code mvn test} 不会启动 Docker。</p>
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(180)
class SmokeIT {

    private static final String IMAGE = "xac-e2e:24.04";
    private static final String UNIT = "xac.demo.service";
    private static final String UNIT_PATH = "/etc/systemd/system/" + UNIT;
    private static final String ENV_PATH = "/etc/xac/xac.demo.env";
    private static final String WANTS_LINK = "/etc/systemd/system/multi-user.target.wants/" + UNIT;
    private static final String PACKAGE_DIR = "/opt/demo";
    private static final String FILELOG_PACKAGE_DIR = "/opt/filelog";
    private static final String BADLOG_PACKAGE_DIR = "/opt/badlog";
    private static final String FILELOG_UNIT = "xac.filelog.service";
    private static final String FILELOG_LOG = "/var/log/filelog/app.log";
    private static final String CLI_JAR = "/opt/xac.jar";
    private static final String WORKDIR = "/opt/demo";
    private static final String[] LINK_NAMES = {"a", "b", "c", "d", "e", "f", "g"};

    @TempDir
    static Path tempDir;

    private static GenericContainer<?> container;
    private static Path buildDir;
    private static Path cliJar;
    private static Path demoJar;
    private static Path demoYaml;
    private static Path filelogYaml;
    private static Path badlogYaml;
    private static Path shadowUnit;
    private static Path shadowYaml;

    @BeforeAll
    static void startContainerAndPrepareFixture() throws Exception {
        configureDockerApiVersion();
        String buildDirProperty = System.getProperty("xac.build.dir");
        buildDir = buildDirProperty == null ? Paths.get("target") : Paths.get(buildDirProperty);
        cliJar = locateCliJar(buildDir);
        demoJar = buildDemoJar(tempDir);
        demoYaml = writeHostFile("xac.yaml", demoYamlText());
        filelogYaml = writeHostFile("filelog-xac.yaml", filelogYamlText());
        badlogYaml = writeHostFile("badlog-xac.yaml", badlogYamlText());
        shadowUnit = writeHostFile("xac.shadow.service", shadowUnitText());
        shadowYaml = writeHostFile("shadow-xac.yaml", shadowYamlText());

        Map<String, String> tmpfs = new HashMap<String, String>();
        tmpfs.put("/run", "rw");
        tmpfs.put("/run/lock", "rw");
        tmpfs.put("/tmp", "rw");

        container = new SystemdContainer(IMAGE)
                .withPrivilegedMode(true)
                .withTmpFs(tmpfs)
                .withCreateContainerCmdModifier(cmd -> {
                    CreateContainerCmd createContainerCmd = cmd;
                    HostConfig hostConfig = createContainerCmd.getHostConfig();
                    if (hostConfig == null) {
                        hostConfig = new HostConfig();
                    }
                    // 本机镜像的 ENTRYPOINT 是 /bin/sh、CMD 是 sleep，必须显式覆盖成
                    // systemd 作为 PID 1 启动，否则会变成 /bin/sh /sbin/init。
                    createContainerCmd.withEntrypoint("/sbin/init");
                    createContainerCmd.withCmd(Collections.<String>emptyList());
                    createContainerCmd.withHostConfig(hostConfig.withCgroupnsMode("host"));
                });
        container.start();
        waitForSystemd();

        exec("mkdir", "-p", PACKAGE_DIR, "/opt/links", "/opt/elsewhere",
                "/opt/links/a", "/opt/links/b", "/opt/links/c", "/opt/links/d",
                "/opt/links/e", "/opt/links/f", "/opt/links/g");
        exec("mkdir", "-p", FILELOG_PACKAGE_DIR, BADLOG_PACKAGE_DIR, "/var/log/filelog");
        copyToContainer(cliJar, CLI_JAR);
        copyToContainer(demoJar, PACKAGE_DIR + "/app.jar");
        copyToContainer(demoYaml, PACKAGE_DIR + "/xac.yaml");
        copyToContainer(demoJar, FILELOG_PACKAGE_DIR + "/app.jar");
        copyToContainer(filelogYaml, FILELOG_PACKAGE_DIR + "/xac.yaml");
        copyToContainer(demoJar, BADLOG_PACKAGE_DIR + "/app.jar");
        copyToContainer(badlogYaml, BADLOG_PACKAGE_DIR + "/xac.yaml");

        // 四种「目标已存在且不是指向源的软链接」的形态，供 apply 的护栏测试使用。
        exec("mkdir", "-p", "/opt/links/b/app.jar");
        exec("sh", "-c", "printf keep > /opt/links/b/app.jar/keep.txt");
        exec("ln", "-s", "/opt/elsewhere", "/opt/links/c/app.jar");
        exec("sh", "-c", "printf plain > /opt/links/d/app.jar");
        exec("ln", "-s", "/opt/links/missing/app.jar", "/opt/links/e/app.jar");
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Order(1)
    void statusReportsDriftBeforeApply() throws Exception {
        ExecResult result = xac("status", PACKAGE_DIR);
        assertExit(1, result, "status before apply");
        String out = result.getStdout();
        assertAscii("status before apply", out);
        assertContains(out, "unit: " + UNIT_PATH + " (missing)");
        assertContains(out, "env: " + ENV_PATH + " (out of sync)");
        assertContains(out, "auto-start: disabled");
        assertContains(out, "drift: unit not installed: " + UNIT_PATH);
        assertContains(out, "drift: env missing: " + ENV_PATH);
        assertContains(out, "drift: auto-start disabled");
        for (int i = 0; i < LINK_NAMES.length; i++) {
            assertContains(out, "drift: path-links[" + i + "] not active: /opt/links/"
                    + LINK_NAMES[i] + "/app.jar");
        }
    }

    @Test
    @Order(2)
    void applyRejectsBlockedPathLinks() throws Exception {
        ExecResult result = xac("apply", PACKAGE_DIR);
        assertExit(2, result, "apply with blocked path-links");
        String out = combined(result);

        // 四种形态都要在写入前被识别，且一起报告。
        assertAscii("blocked apply", out);
        assertContains(out, "path-links[1]: target exists and is not a symlink to source,"
                + " handle it manually: /opt/links/b/app.jar");
        assertContains(out, "path-links[2]: target exists and is not a symlink to source,"
                + " handle it manually: /opt/links/c/app.jar");
        assertContains(out, "path-links[3]: target exists and is not a symlink to source,"
                + " handle it manually: /opt/links/d/app.jar");
        assertContains(out, "path-links[4]: target exists and is not a symlink to source,"
                + " handle it manually: /opt/links/e/app.jar");

        // 什么都没写、没启动。
        assertFalse(exists(UNIT_PATH), "unit must not be written on blocked apply");
        assertFalse(exists(ENV_PATH), "env must not be written on blocked apply");
        assertEquals(1, exec("pgrep", "-f", "/opt/demo/app.jar").getExitCode(),
                "no demo process may be started on blocked apply");
        assertFalse(result.getStdout().contains("systemctl restart"),
                "blocked apply must not reach the restart action");

        // 原有内容原样保留。
        assertEquals("keep", cat("/opt/links/b/app.jar/keep.txt"));
        assertEquals("/opt/elsewhere", readlink("/opt/links/c/app.jar"));
        assertEquals("plain", cat("/opt/links/d/app.jar"));
        assertEquals("/opt/links/missing/app.jar", readlink("/opt/links/e/app.jar"));
        assertEquals("", exec("find", "/etc", "/opt", "-name", "*.bak").getStdout().trim(),
                "no .bak files may be created");
    }

    @Test
    @Order(3)
    void applyInstallsServiceAndLinks() throws Exception {
        exec("rm", "-rf", "/opt/links/b/app.jar");
        exec("rm", "-f", "/opt/links/c/app.jar", "/opt/links/d/app.jar", "/opt/links/e/app.jar");

        ExecResult result = xac("apply", PACKAGE_DIR);
        assertExit(0, result, "apply after removing blockers");
        String out = result.getStdout();
        assertAscii("apply", out);
        assertContains(out, pathLinksLine("create"));
        assertContains(out, "write " + UNIT_PATH + " (0644)");
        assertContains(out, "write " + ENV_PATH + " (0600)");
        assertContains(out, "systemctl enable " + UNIT);
        assertContains(out, "systemctl restart " + UNIT);

        assertEquals("644", mode(UNIT_PATH), "unit file mode");
        assertEquals("600", mode(ENV_PATH), "env file mode");
        assertEquals("enabled", showValue(UNIT, "UnitFileState"));
        waitForActive("active");

        assertTrue(isLink(WANTS_LINK), "multi-user.target.wants link must exist");
        assertEquals(UNIT_PATH, readlink(WANTS_LINK));

        String journal = waitForJournal("READY pid=");
        assertContains(journal, "[-Xmx256m]");
        assertContains(journal, "[-Dgreeting=hello world]");
        assertContains(journal, "[-Dpct=100%]");
        assertContains(journal, "[-Ddollar=$]");
        assertContains(journal, "GREETING=hello world");
        assertContains(journal, "CWD=" + WORKDIR);
        assertContains(journal, "XMX=268435456");

        for (String name : LINK_NAMES) {
            String target = "/opt/links/" + name + "/app.jar";
            assertTrue(isLink(target), "path-link must be a symlink: " + target);
            assertEquals(PACKAGE_DIR + "/app.jar", readlink(target), "path-link source: " + target);
        }

        ExecResult status = xac("status", PACKAGE_DIR);
        assertExit(0, status, "status after apply");
        String statusOut = status.getStdout();
        assertAscii("status after apply", statusOut);
        assertContains(statusOut, "unit: " + UNIT_PATH + " (in sync)");
        assertContains(statusOut, "env: " + ENV_PATH + " (in sync)");
        assertContains(statusOut, "auto-start: enabled");
        assertContains(statusOut, "active: active (running)");
        assertFalse(statusOut.contains("drift:"), "healthy status must not report drift");

        // 前缀：name: demo 只能产生 xac.demo.*，不能产生 demo.*。
        assertFalse(exists("/etc/systemd/system/demo.service"));
        assertFalse(exists("/etc/xac/demo.env"));
        assertEquals("not-found", showValue("demo.service", "LoadState"));
        String unitContent = cat(UNIT_PATH);
        assertContains(unitContent, "EnvironmentFile=" + ENV_PATH);
        assertContains(unitContent, "SyslogIdentifier=xac.demo");
    }

    @Test
    @Order(4)
    void dryRunChangesNothing() throws Exception {
        String unitStat = stat("%i %Y", UNIT_PATH);
        String envStat = stat("%i %Y", ENV_PATH);
        String pidBefore = showValue(UNIT, "MainPID");

        ExecResult result = xac("apply", PACKAGE_DIR, "--dry-run");
        assertExit(0, result, "dry-run apply");
        assertContains(result.getStdout(), "would ");

        assertEquals(unitStat, stat("%i %Y", UNIT_PATH), "dry-run must not rewrite unit");
        assertEquals(envStat, stat("%i %Y", ENV_PATH), "dry-run must not rewrite env");
        assertEquals(pidBefore, showValue(UNIT, "MainPID"), "dry-run must not restart service");
    }

    @Test
    @Order(5)
    void reapplyKeepsLinkInodeAndRestarts() throws Exception {
        String linkInode = stat("%i", "/opt/links/a/app.jar");
        String pidBefore = showValue(UNIT, "MainPID");

        ExecResult result = xac("apply", PACKAGE_DIR);
        assertExit(0, result, "re-apply");
        assertEquals(linkInode, stat("%i", "/opt/links/a/app.jar"),
                "existing correct symlink must be left untouched");
        String pidAfter = waitForPidChange(pidBefore);
        assertNotEquals(pidBefore, pidAfter, "re-apply must restart the service");
        assertEquals("", exec("find", "/etc", "/opt", "-name", "*.bak").getStdout().trim(),
                "re-apply must not create .bak files");
    }

    @Test
    @Order(6)
    void logsPrintApplicationOutput() throws Exception {
        ExecResult result = xac("logs", PACKAGE_DIR);
        assertExit(0, result, "logs");
        assertContains(result.getStdout(), "READY pid=");

        ExecResult limited = xac("logs", PACKAGE_DIR, "-n", "5");
        assertExit(0, limited, "logs -n 5");
        String text = limited.getStdout().trim();
        assertFalse(text.isEmpty(), "logs -n 5 must return output");
        int lines = text.split("\n").length;
        assertTrue(lines <= 5, "logs -n 5 must bound the line count, got " + lines);
    }

    @Test
    @Order(7)
    void stopStartAndStatusFollowServiceState() throws Exception {
        ExecResult stop = exec("systemctl", "stop", UNIT);
        assertEquals(0, stop.getExitCode(), "systemctl stop: " + stop.getStderr());
        waitForActive("inactive");
        assertEquals("success", showValue(UNIT, "Result"),
                "SuccessExitStatus=143 must make SIGTERM shutdown successful");
        waitForJournal("STOPPING");

        ExecResult stoppedStatus = xac("status", PACKAGE_DIR);
        assertExit(1, stoppedStatus, "status while stopped");
        assertContains(stoppedStatus.getStdout(), "active: inactive (dead)");

        ExecResult start = exec("systemctl", "start", UNIT);
        assertEquals(0, start.getExitCode(), "systemctl start: " + start.getStderr());
        waitForActive("active");
        assertExit(0, xac("status", PACKAGE_DIR), "status after start");

        exec("sh", "-c", "printf '\\nXAC_EXTRA=1\\n' >> " + ENV_PATH);
        ExecResult drifted = xac("status", PACKAGE_DIR);
        assertExit(1, drifted, "status after env edit");
        assertContains(drifted.getStdout(), "env: " + ENV_PATH + " (out of sync)");

        assertExit(0, xac("apply", PACKAGE_DIR), "apply to restore env");
        waitForActive("active");
        assertExit(0, xac("status", PACKAGE_DIR), "status after restoring env");
    }

    @Test
    @Order(8)
    void destroyRemovesOwnedStateOnly() throws Exception {
        // 把两个受管链接换成用户自己的东西，destroy 必须原样保留。
        exec("rm", "-f", "/opt/links/f/app.jar");
        exec("sh", "-c", "printf keep > /opt/links/f/app.jar");
        exec("rm", "-f", "/opt/links/g/app.jar");
        exec("ln", "-s", "/opt/elsewhere", "/opt/links/g/app.jar");

        ExecResult result = xac("destroy", PACKAGE_DIR, "--yes");
        assertExit(0, result, "destroy --yes");
        String out = combined(result);
        assertAscii("destroy", out);
        assertContains(out, "systemctl stop " + UNIT);
        assertContains(out, "systemctl disable " + UNIT);
        assertContains(out, "remove " + UNIT_PATH);
        assertContains(out, "remove " + ENV_PATH);
        assertContains(out, pathLinksLine("remove"));

        assertFalse(exists(UNIT_PATH), "unit must be removed");
        assertFalse(exists(ENV_PATH), "env must be removed");
        assertFalse(isLink(WANTS_LINK), "wants link must be removed");
        assertEquals("not-found", showValue(UNIT, "LoadState"));
        assertEquals(1, exec("pgrep", "-f", "/opt/demo/app.jar").getExitCode(),
                "service process must be gone");

        for (String name : new String[]{"a", "b", "c", "d", "e"}) {
            assertFalse(isLink("/opt/links/" + name + "/app.jar"),
                    "managed link must be removed: " + name);
        }
        assertEquals("", exec("ls", "-A", "/opt/links/b").getStdout().trim(),
                "managed link directory must be left empty");

        assertTrue(exists("/opt/links/f/app.jar"), "real file must survive destroy");
        assertEquals("keep", cat("/opt/links/f/app.jar"));
        assertTrue(isLink("/opt/links/g/app.jar"), "foreign symlink must survive destroy");
        assertEquals("/opt/elsewhere", readlink("/opt/links/g/app.jar"));
    }

    @Test
    @Order(9)
    void shadowUnitGuardRejectsForeignUnit() throws Exception {
        exec("mkdir", "-p", "/opt/shadow");
        copyToContainer(shadowUnit, "/usr/lib/systemd/system/xac.shadow.service");
        copyToContainer(shadowYaml, "/opt/shadow/xac.yaml");
        exec("systemctl", "daemon-reload");
        assertEquals("/usr/lib/systemd/system/xac.shadow.service",
                showValue("xac.shadow.service", "FragmentPath"));

        ExecResult result = xac("apply", "/opt/shadow");
        assertExit(2, result, "apply of a shadowed name");
        String out = combined(result);
        assertAscii("shadow unit guard", out);
        assertContains(out, "unit: unit with same name exists and is not managed by xac:"
                + " /usr/lib/systemd/system/xac.shadow.service, handle it manually");

        assertFalse(exists("/etc/systemd/system/xac.shadow.service"),
                "shadow guard must not write the unit");
        assertFalse(exists("/etc/xac/xac.shadow.env"),
                "shadow guard must not write the env");
        assertTrue(exists("/usr/lib/systemd/system/xac.shadow.service"),
                "vendor unit must be untouched");
        assertNotEquals("active", showValue("xac.shadow.service", "ActiveState"));
    }

    @Test
    @Order(10)
    void logsReadConfiguredFileInsteadOfJournald() throws Exception {
        ExecResult applied = xac("apply", FILELOG_PACKAGE_DIR);
        assertExit(0, applied, "apply filelog");
        waitForActive(FILELOG_UNIT, "active");

        // 服务自己写的日志文件：READY 行只会出现在文件里，不会出现在 stdout/journal 里。
        String fileContent = waitForFileText(FILELOG_LOG, "FILELOG-READY");
        assertContains(fileContent, "FILELOG-READY");

        ExecResult logs = xac("logs", FILELOG_PACKAGE_DIR, "-n", "2");
        assertExit(0, logs, "logs from configured file");
        String out = logs.getStdout();
        assertAscii("file logs", out);
        assertContains(out, "FILELOG-");
        assertFalse(out.contains("READY pid="),
                "file mode must not read journald output:\n" + out);
        assertTrue(out.trim().split("\n").length <= 2,
                "logs -n 2 must bound the line count, got:\n" + out);

        ExecResult missing = xac("logs", BADLOG_PACKAGE_DIR);
        assertExit(2, missing, "logs with missing log file");
        assertContains(missing.getStderr(),
                "log file not found: /var/log/filelog/missing.log");

        ExecResult destroyed = xac("destroy", FILELOG_PACKAGE_DIR, "--yes");
        assertExit(0, destroyed, "destroy filelog");
        assertFalse(exists("/etc/systemd/system/" + FILELOG_UNIT),
                "filelog unit must be removed");
        assertFalse(exists("/etc/xac/xac.filelog.env"), "filelog env must be removed");
        assertTrue(exists(FILELOG_LOG), "destroy must not remove the log file it does not own");
        assertContains(cat(FILELOG_LOG), "FILELOG-READY");
    }

    @Test
    @Order(11)
    void tuiRefusesToStartWithoutATty() throws Exception {
        // 容器里 exec 没有 TTY：tui 必须一行提示后退出 2，绝不尝试打开终端。
        ExecResult result = xac("tui", PACKAGE_DIR);

        assertExit(2, result, "tui without a TTY");
        assertContains(result.getStderr(), "tui requires an interactive terminal");
        assertEquals("", result.getStdout(), "tui without a TTY must not write to stdout");
        assertAscii("tui without a TTY", combined(result));
    }

    @Test
    @Order(12)
    void tuiRendersInARealPtyAndQuits() throws Exception {
        // 真 PTY：runner 脚本里 script 分配 pty，stty 设尺寸；按键按「屏幕上先看到什么」
        // 再送，不靠固定 sleep，慢机器上也不会和渲染赛跑。
        try {
            startTuiPty();
            awaitScreenContains("a apply", 60000L);
            sendKeys("?");
            awaitScreenContains("help is not implemented yet", 20000L);
            sendKeys("q");

            assertEquals(0, awaitTuiExit(20000L), "tui must exit 0 after q:\n" + tuiScreen());
            String raw = tuiRawOutput();
            // 真实终端路径一定产生 ANSI 控制序列；非 TTY 分支不会走到这里。
            assertContains(raw, "\u001b[");
            assertFalse(raw.contains("tui requires an interactive terminal"),
                    "pty run must not take the non-tty fallback:\n" + tuiScreen());
            assertContains(tuiScreen(), "xac.demo");
        } finally {
            stopTuiPty();
        }
    }

    @Test
    @Order(13)
    void tuiApplyRunsTheRealLifecycle() throws Exception {
        // 先清掉前面用例留下的链接和 unit/env，再从 TUI 里用真实按键 a -> y 装回来。
        exec("sh", "-c", "rm -rf /opt/links/*/app.jar");
        exec("rm", "-f", UNIT_PATH, ENV_PATH);
        assertFalse(exists(ENV_PATH), "env file must be gone before the tui apply");

        try {
            startTuiPty();
            awaitScreenContains("a apply", 60000L);
            sendKeys("a");
            awaitScreenContains("y apply", 20000L);
            sendKeys("y");
            awaitScreenContains("apply ok", 90000L);
            sendKeys("q");

            assertEquals(0, awaitTuiExit(20000L), "tui apply run must exit 0:\n" + tuiScreen());
            assertTrue(exists(ENV_PATH), "tui apply must rewrite the env file");
            assertContains(cat(ENV_PATH), "GREETING=\"hello world\"");
            assertTrue(exists(UNIT_PATH), "tui apply must rewrite the unit file");
            assertEquals("active", exec("systemctl", "is-active", UNIT).getStdout().trim(),
                    "service must be active after the tui apply");
        } finally {
            stopTuiPty();
        }
    }

    private static void startTuiPty() throws Exception {
        Path runner = writeHostFile("tui-run.sh", ""
                + "#!/bin/sh\n"
                + "exec 3<>/tmp/xac-tui-in\n"
                + "script -qec 'stty rows 30 cols 100; TERM=xterm-256color exec java -jar "
                + CLI_JAR + " tui " + PACKAGE_DIR + "' /dev/null <&3 > /tmp/xac-tui-out 2>&1\n"
                + "printf '%s' \"$?\" > /tmp/xac-tui-rc\n");
        exec("rm", "-f", "/tmp/xac-tui-in", "/tmp/xac-tui-out", "/tmp/xac-tui-rc");
        exec("sh", "-c", "mkfifo /tmp/xac-tui-in");
        copyToContainer(runner, "/opt/xac-tui-run.sh");
        exec("chmod", "755", "/opt/xac-tui-run.sh");
        // 用 detached exec 启动：execInContainer 会在命令返回时收掉整个会话，
        // 后台 TUI 会被一起杀掉；detached 的 exec 由 daemon 持有，进程继续跑。
        String execId = container.getDockerClient()
                .execCreateCmd(container.getContainerId())
                .withCmd("/bin/sh", "-c",
                        "setsid /opt/xac-tui-run.sh >/tmp/xac-tui-log 2>&1 < /dev/null")
                .withAttachStdin(false)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .withTty(false)
                .exec()
                .getId();
        container.getDockerClient().execStartCmd(execId)
                .withDetach(true)
                .exec(new ResultCallback.Adapter<Frame>());
    }

    private static void stopTuiPty() throws Exception {
        exec("sh", "-c", "pkill -f 'tui /opt/demo' || true");
        exec("rm", "-f", "/tmp/xac-tui-in");
    }

    private static void sendKeys(String keys) throws Exception {
        exec("sh", "-c", "printf '%s' '" + keys + "' > /tmp/xac-tui-in");
    }

    private static String tuiRawOutput() throws IOException, InterruptedException {
        if (!exists("/tmp/xac-tui-out")) {
            return "";
        }
        return exec("cat", "/tmp/xac-tui-out").getStdout();
    }

    private static String tuiScreen() throws IOException, InterruptedException {
        return TerminalCapture.render(tuiRawOutput(), 30, 100);
    }

    private static void awaitScreenContains(String needle, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String screen = "";
        while (System.currentTimeMillis() <= deadline) {
            screen = tuiScreen();
            if (screen.contains(needle)) {
                return;
            }
            Thread.sleep(200L);
        }
        fail("timed out waiting for [" + needle + "] on the tui screen:\n" + screen
                + "\nraw tail:\n" + tail(tuiRawOutput(), 4000)
                + "\ndiagnostics:\n" + tuiDiagnostics());
    }

    private static int awaitTuiExit(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() <= deadline) {
            if (exists("/tmp/xac-tui-rc")) {
                return Integer.parseInt(exec("cat", "/tmp/xac-tui-rc").getStdout().trim());
            }
            Thread.sleep(200L);
        }
        fail("tui did not exit in time; screen:\n" + tuiScreen());
        return -1;
    }

    private static String tuiDiagnostics() {
        StringBuilder text = new StringBuilder();
        try {
            text.append(exec("sh", "-c",
                    "ls -l /tmp/xac-tui-* 2>&1; echo ---LOG---; cat /tmp/xac-tui-log 2>&1;"
                            + " echo ---PS---; ps -ef | grep -E 'java -jar|script -qec' | grep -v grep").getStdout());
        } catch (Exception e) {
            text.append("diagnostics failed: ").append(e);
        }
        return text.toString();
    }

    private static String tail(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private static ExecResult xac(String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<String>();
        argv.add("java");
        argv.add("-jar");
        argv.add(CLI_JAR);
        Collections.addAll(argv, args);
        return container.execInContainer(StandardCharsets.UTF_8, argv.toArray(new String[argv.size()]));
    }

    private static ExecResult exec(String... argv) throws IOException, InterruptedException {
        return container.execInContainer(StandardCharsets.UTF_8, argv);
    }

    private static String combined(ExecResult result) {
        return result.getStdout() + "\n" + result.getStderr();
    }

    private static void assertExit(int expected, ExecResult result, String what) {
        if (result.getExitCode() != expected) {
            fail(what + ": expected exit " + expected + " but was " + result.getExitCode()
                    + "\nstdout:\n" + result.getStdout()
                    + "\nstderr:\n" + result.getStderr());
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle),
                "expected to find [" + needle + "] in:\n" + haystack);
    }

    /** 运行时输出必须是纯 ASCII，且不能被编码回退成 '?'。 */
    private static void assertAscii(String what, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertTrue(c < 0x80, what + " must be ASCII, found U+"
                    + Integer.toHexString(c) + " in:\n" + text);
        }
        assertFalse(text.contains("?"), what + " must not contain '?' (encoding fallback):\n" + text);
    }

    /** path-links 动作的完整描述行；链接按 xac.yaml 中的顺序列出。 */
    private static String pathLinksLine(String action) {
        StringBuilder line = new StringBuilder(action).append(" path-links: [");
        for (int i = 0; i < LINK_NAMES.length; i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append("app.jar:/opt/links/").append(LINK_NAMES[i]).append("/app.jar");
        }
        return line.append(']').toString();
    }

    private static boolean exists(String path) throws IOException, InterruptedException {
        return exec("test", "-e", path).getExitCode() == 0;
    }

    private static boolean isLink(String path) throws IOException, InterruptedException {
        return exec("test", "-L", path).getExitCode() == 0;
    }

    private static String mode(String path) throws IOException, InterruptedException {
        return stat("%a", path);
    }

    private static String stat(String format, String path) throws IOException, InterruptedException {
        ExecResult result = exec("stat", "-c", format, path);
        assertEquals(0, result.getExitCode(), "stat " + path + ": " + result.getStderr());
        return result.getStdout().trim();
    }

    private static String readlink(String path) throws IOException, InterruptedException {
        ExecResult result = exec("readlink", path);
        assertEquals(0, result.getExitCode(), "readlink " + path + ": " + result.getStderr());
        return result.getStdout().trim();
    }

    private static String cat(String path) throws IOException, InterruptedException {
        ExecResult result = exec("cat", path);
        assertEquals(0, result.getExitCode(), "cat " + path + ": " + result.getStderr());
        return result.getStdout().trim();
    }

    private static String showValue(String unit, String property) throws IOException, InterruptedException {
        ExecResult result = exec("systemctl", "show", unit, "--property=" + property, "--value");
        assertEquals(0, result.getExitCode(),
                "systemctl show " + unit + " " + property + ": " + result.getStderr());
        return result.getStdout().trim();
    }

    private static String journal(int lines) throws IOException, InterruptedException {
        ExecResult result = exec("journalctl", "-u", UNIT, "--no-pager", "-n", Integer.toString(lines));
        assertEquals(0, result.getExitCode(), "journalctl: " + result.getStderr());
        return result.getStdout();
    }

    private static void waitForActive(String expected) throws IOException, InterruptedException {
        waitForActive(UNIT, expected);
    }

    private static void waitForActive(String unit, String expected)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 30000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = showValue(unit, "ActiveState");
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(200L);
        }
        fail(unit + " did not reach " + expected + ", last ActiveState=" + last);
    }

    private static String waitForFileText(String path, String needle)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            ExecResult result = exec("cat", path);
            last = result.getExitCode() == 0 ? result.getStdout() : "";
            if (last.contains(needle)) {
                return last;
            }
            Thread.sleep(200L);
        }
        fail("file " + path + " did not contain [" + needle + "], last content:\n" + last);
        return last;
    }

    private static String waitForPidChange(String before) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15000L;
        String last = before;
        while (System.currentTimeMillis() < deadline) {
            last = showValue(UNIT, "MainPID");
            if (!before.equals(last)) {
                return last;
            }
            Thread.sleep(200L);
        }
        fail("MainPID did not change from " + before + ", last=" + last);
        return last;
    }

    private static String waitForJournal(String needle) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = journal(500);
            if (last.contains(needle)) {
                return last;
            }
            Thread.sleep(300L);
        }
        fail("journal did not contain [" + needle + "], last journal:\n" + last);
        return last;
    }

    private static void waitForSystemd() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 60000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            ExecResult result = exec("systemctl", "is-system-running");
            last = result.getStdout().trim();
            // 容器里 getty 等服务起不来，degraded 是正常状态。
            if ("running".equals(last) || "degraded".equals(last)) {
                return;
            }
            Thread.sleep(250L);
        }
        fail("systemd did not reach running/degraded within 60s, last state=[" + last
                + "]\ncontainer log:\n" + container.getLogs());
    }

    private static void copyToContainer(Path hostPath, String containerPath)
            throws IOException, InterruptedException {
        container.copyFileToContainer(
                MountableFile.forHostPath(hostPath.toAbsolutePath().toString(), 0644), containerPath);
    }

    private static Path writeHostFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Testcontainers 1.19.x 内置的 docker-java 在无法协商版本时会退回 API 1.32，
     * 而 Docker 29 已把最低 API 提到 1.40，连 ping 都会被 400 拒绝。docker-java 支持用
     * 系统属性 {@code api.version} 覆盖，这里问一下本机 docker CLI 服务端实际版本再设置，
     * 避免把某个固定版本写死（旧 Docker 会拒绝过新的版本）。
     */
    private static void configureDockerApiVersion() {
        if (System.getProperty("api.version") != null) {
            return;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "docker", "version", "--format", "{{.Server.APIVersion}}")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8);
            try {
                char[] buffer = new char[256];
                int read;
                while ((read = reader.read(buffer)) != -1 && output.length() < 64) {
                    output.append(buffer, 0, read);
                }
            } finally {
                reader.close();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                return;
            }
            String[] lines = output.toString().split("\\n");
            for (String line : lines) {
                String candidate = line.trim();
                if (candidate.matches("\\d+\\.\\d+")) {
                    System.setProperty("api.version", candidate);
                }
            }
        } catch (IOException e) {
            // 没有 docker CLI 时保持 Testcontainers 的默认行为。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    private static Path locateCliJar(Path target) throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target, "*.jar")) {
            for (Path candidate : stream) {
                String fileName = candidate.getFileName().toString();
                // shade 在 package 后会留下 original-*.jar 与 *-shaded.jar（两者也可能带 Main-Class），
                // 只认最终产物，否则会找到多个候选。
                if (fileName.startsWith("original-") || fileName.endsWith("-shaded.jar")) {
                    continue;
                }
                try (JarFile jar = new JarFile(candidate.toFile())) {
                    Manifest manifest = jar.getManifest();
                    if (manifest == null) {
                        continue;
                    }
                    String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                    if ("io.xac.systemd.cli.Main".equals(mainClass)) {
                        candidates.add(candidate);
                    }
                } catch (IOException ignored) {
                    // 不是可读 jar，忽略。
                }
            }
        }
        if (candidates.size() != 1) {
            fail("expected exactly one CLI jar with Main-Class io.xac.systemd.cli.Main under "
                    + target.toAbsolutePath() + ", found " + candidates);
        }
        return candidates.get(0);
    }

    private static Path buildDemoJar(Path dir) throws Exception {
        Path classes = Paths.get(DemoApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(classes)) {
            fail("test classes directory not found: " + classes);
        }
        Path demoClasses = classes.resolve("io/xac/systemd/e2e");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DemoApp.class.getName());

        Path jar = dir.resolve("demo-app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             DirectoryStream<Path> stream = Files.newDirectoryStream(demoClasses, "DemoApp*.class")) {
            for (Path classFile : stream) {
                out.putNextEntry(new JarEntry("io/xac/systemd/e2e/" + classFile.getFileName()));
                try (InputStream in = Files.newInputStream(classFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String demoYamlText() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: demo\n");
        yaml.append("description: e2e demo\n");
        yaml.append("artifact:\n");
        yaml.append("  jar: app.jar\n");
        yaml.append("runtime:\n");
        yaml.append("  java: /usr/bin/java\n");
        yaml.append("  opts:\n");
        yaml.append("    - -Xmx256m\n");
        yaml.append("env:\n");
        yaml.append("  GREETING: \"hello world\"\n");
        yaml.append("properties:\n");
        yaml.append("  greeting: \"${GREETING}\"\n");
        yaml.append("  pct: \"100%\"\n");
        yaml.append("  dollar: \"$$\"\n");
        yaml.append("workdir: ").append(WORKDIR).append("\n");
        yaml.append("path-links:\n");
        for (String name : LINK_NAMES) {
            yaml.append("  - app.jar:/opt/links/").append(name).append("/app.jar\n");
        }
        yaml.append("lifecycle:\n");
        yaml.append("  auto-start: true\n");
        yaml.append("  restart: on-failure\n");
        yaml.append("  restart-sec: 2\n");
        yaml.append("  timeout-stop-sec: 10\n");
        return yaml.toString();
    }

    private static String filelogYamlText() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: filelog\n");
        yaml.append("description: e2e file log\n");
        yaml.append("artifact:\n");
        yaml.append("  jar: app.jar\n");
        yaml.append("runtime:\n");
        yaml.append("  java: /usr/bin/java\n");
        yaml.append("  opts:\n");
        yaml.append("    - -Dlog.file=").append(FILELOG_LOG).append("\n");
        yaml.append("logs:\n");
        yaml.append("  file: ").append(FILELOG_LOG).append("\n");
        yaml.append("workdir: ").append(FILELOG_PACKAGE_DIR).append("\n");
        yaml.append("lifecycle:\n");
        yaml.append("  restart-sec: 2\n");
        yaml.append("  timeout-stop-sec: 10\n");
        return yaml.toString();
    }

    private static String badlogYamlText() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: badlog\n");
        yaml.append("artifact:\n");
        yaml.append("  jar: app.jar\n");
        yaml.append("runtime:\n");
        yaml.append("  java: /usr/bin/java\n");
        yaml.append("logs:\n");
        yaml.append("  file: /var/log/filelog/missing.log\n");
        return yaml.toString();
    }

    private static String shadowYamlText() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: shadow\n");
        yaml.append("artifact:\n");
        yaml.append("  jar: /opt/demo/app.jar\n");
        yaml.append("runtime:\n");
        yaml.append("  java: /usr/bin/java\n");
        yaml.append("  opts: []\n");
        yaml.append("workdir: /opt/demo\n");
        return yaml.toString();
    }

    private static String shadowUnitText() {
        return "[Unit]\n"
                + "Description=vendor shadow unit\n"
                + "\n"
                + "[Service]\n"
                + "ExecStart=/bin/true\n";
    }

    /**
     * 用具体子类固定 {@code SELF} 类型，避免 {@code GenericContainer<?>} 通配符在链式调用里
     * 触发泛型边界错误。
     */
    private static final class SystemdContainer extends GenericContainer<SystemdContainer> {

        private SystemdContainer(String image) {
            super(DockerImageName.parse(image));
        }
    }
}
