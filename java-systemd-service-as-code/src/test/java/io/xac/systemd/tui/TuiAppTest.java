package io.xac.systemd.tui;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TUI 外壳的行为契约：不启动真实终端，按键走脚本、屏幕走 RecordingDisplay，
 * 只断言画布内容（design §8：能在视图模型断言的，不在终端断言）。
 */
class TuiAppTest {

    private static final String SHOW_OUTPUT =
            "LoadState=loaded\n"
                    + "ActiveState=active\n"
                    + "SubState=running\n"
                    + "UnitFileState=enabled\n"
                    + "MainPID=4321\n"
                    + "ActiveEnterTimestamp=Thu 2026-11-13 02:00:00 CST\n"
                    + "FragmentPath=/etc/systemd/system/xac.demo.service\n";

    @TempDir
    Path pkg;

    private final RecordingRunner runner = new RecordingRunner();
    private final FakeProbe probe = new FakeProbe();

    private static final class FakeProbe implements EnvironmentProbe {
        boolean root = true;

        @Override
        public boolean isRoot() {
            return root;
        }

        @Override
        public boolean systemdRunning() {
            return true;
        }

        @Override
        public boolean fileReadable(Path path) {
            return Files.isRegularFile(path);
        }

        @Override
        public boolean executable(Path path) {
            return true;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.write(pkg.resolve("xac.yaml"), String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "").getBytes(StandardCharsets.UTF_8));
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW_OUTPUT, ""));
    }

    private ServicePackage openPackage() throws InvalidSpecException {
        return ServicePackage.open(pkg, runner, probe,
                new SystemdTarget(pkg.resolve("units"), pkg.resolve("env")));
    }

    private TuiApp app(RecordingDisplay display, KeySource keys) throws InvalidSpecException {
        return new TuiApp(openPackage(), display, keys);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void drawsTitleStatusAndKeyBar() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        assertFalse(app.tick());

        String expectedTitle = "┌ xac.demo " + repeat('─', 36)
                + " running · auto-start on · root ┐";
        assertEquals(expectedTitle, display.line(0));
        assertEquals(80, display.line(0).length());

        assertTrue(display.line(1).contains("unit"), display.line(1));
        assertTrue(display.line(2).contains("env"), display.line(2));
        assertTrue(display.line(3).contains("process"), display.line(3));
        assertTrue(display.line(3).contains("running"), display.line(3));
        assertTrue(display.line(4).contains("links"), display.line(4));

        String keyBar = display.line(23);
        assertEquals(80, keyBar.length());
        assertTrue(keyBar.startsWith("└ "), keyBar);
        assertTrue(keyBar.contains("q quit"), keyBar);
        assertTrue(keyBar.contains("? help"), keyBar);
        assertTrue(keyBar.endsWith("┘"), keyBar);
    }

    @Test
    void quitKeyStopsTheLoop() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        assertFalse(app.tick());
        assertFalse(app.running());
        assertFalse(app.tick());
        assertEquals(1, display.shows());
    }

    @Test
    void qCharacterAlsoQuits() throws Exception {
        TuiApp app = app(new RecordingDisplay(80, 24),
                new ScriptedKeySource(KeyEvent.character('q')));

        assertFalse(app.tick());
        assertFalse(app.running());
    }

    @Test
    void escapeAlsoQuits() throws Exception {
        TuiApp app = app(new RecordingDisplay(80, 24),
                new ScriptedKeySource(KeyEvent.of(NavKey.ESC)));

        assertFalse(app.tick());
        assertFalse(app.running());
    }

    @Test
    void narrowScreenHidesKeyBar() throws Exception {
        RecordingDisplay display = new RecordingDisplay(60, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        assertFalse(app.tick());

        String expectedTitle = "┌ xac.demo " + repeat('─', 16)
                + " running · auto-start on · root ┐";
        assertEquals(expectedTitle, display.line(0));
        assertEquals(60, display.line(0).length());

        String keyBar = display.line(23);
        assertEquals(60, keyBar.length());
        assertTrue(keyBar.contains("? help"), keyBar);
        assertFalse(keyBar.contains("q quit"), keyBar);
        assertTrue(keyBar.endsWith("┘"), keyBar);
    }

    @Test
    void resizeRepaintsAtTheNewSize() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.character('x')));

        display.setSize(60, 20);
        assertTrue(app.tick());

        assertNotNull(display.last());
        assertEquals(60, display.last().width());
        assertEquals(20, display.last().height());
        assertEquals(60, display.line(0).length());
        assertTrue(display.line(19).endsWith("┘"), display.line(19));
    }

    @Test
    void titleMarksNotRoot() throws Exception {
        probe.root = false;
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        app.tick();

        assertTrue(display.line(0).contains("not root"), display.line(0));
        assertFalse(display.line(0).contains("· root ┐"), display.line(0));
    }

    @Test
    void refreshKeyRerunsStatus() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(
                KeyEvent.character('R'), KeyEvent.of(NavKey.QUIT)));

        assertFalse(app.tick());
        assertTrue(app.statusNote().contains("refreshed"), app.statusNote());
        assertEquals(2, runner.calls().size());
    }

    @Test
    void keysBeforeQuitAreConsumedInOneTick() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        ScriptedKeySource keys = new ScriptedKeySource(
                KeyEvent.character('x'), KeyEvent.of(NavKey.DOWN), KeyEvent.of(NavKey.QUIT));
        TuiApp app = app(display, keys);

        assertFalse(app.tick());
        // 一次 tick 把队列里的事件全部消费掉，不会留下未处理的退出键。
        assertTrue(keys.polls() >= 4, "polls=" + keys.polls());
    }
}
