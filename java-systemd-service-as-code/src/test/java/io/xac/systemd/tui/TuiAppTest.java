package io.xac.systemd.tui;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.LogSourceException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.logs.LogSink;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TUI 外壳的行为契约：不启动真实终端，按键走脚本、屏幕走 RecordingDisplay，
 * 日志源走 fake {@link LogStream}，时间走固定 {@link Clock} 或 tick(now)，测试不 sleep。
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

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(0L), ZoneOffset.UTC);

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

    /** 每次 show 都抛同一个 RuntimeException，用来验证 TuiApp 的异常收口。 */
    private static final class FailingDisplay implements Display {

        private final RuntimeException failure;
        int shows;

        FailingDisplay(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void show(Canvas canvas) {
            shows++;
            throw failure;
        }

        @Override
        public Size size() {
            return new Size(80, 24);
        }

        @Override
        public void close() {
        }
    }

    /** 可控日志源：start 只记录并放行回调，close 唤醒阻塞中的读取线程。 */
    private static final class FakeLogStream implements LogStream {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile boolean blockUntilClosed;
        volatile boolean throwOnStart;
        volatile boolean closed;
        volatile int requestedLines = -1;
        volatile LogSink stdout;
        volatile LogSink stderr;

        @Override
        public void start(int lines, LogSink sink) {
            start(lines, sink, sink);
        }

        @Override
        public void start(int lines, LogSink stdoutSink, LogSink stderrSink) {
            if (throwOnStart) {
                throw new IllegalStateException("boom");
            }
            requestedLines = lines;
            stdout = stdoutSink;
            stderr = stderrSink;
            started.countDown();
            if (!blockUntilClosed) {
                return;
            }
            try {
                closedLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isFollow() {
            return false;
        }

        @Override
        public void close() {
            closed = true;
            closedLatch.countDown();
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS), "log stream was not started");
        }

        void emit(String line) {
            LogSink sink = stdout;
            assertNotNull(sink, "stdout sink not captured");
            sink.accept(line);
        }

        void emitError(String line) {
            LogSink sink = stderr;
            assertNotNull(sink, "stderr sink not captured");
            sink.accept(line);
        }
    }

    /**
     * 每个 tick 最多消费一个按键：先返回一个事件，下一次调用返回 null 结束本批；
     * 队列空时整批为空，因此可以在两次按键之间插入不按键的 tick。
     */
    private static final class QueuedKeySource implements KeySource {

        private final Deque<KeyEvent> events = new ArrayDeque<KeyEvent>();
        private boolean yielded;

        QueuedKeySource(KeyEvent... events) {
            enqueue(events);
        }

        void enqueue(KeyEvent... more) {
            for (KeyEvent event : more) {
                events.add(event);
            }
        }

        @Override
        public KeyEvent next(long timeoutMillis) {
            if (yielded) {
                yielded = false;
                return null;
            }
            KeyEvent event = events.poll();
            if (event == null) {
                return null;
            }
            yielded = true;
            return event;
        }
    }

    /** 可切换为抛异常的状态查询，用来验证轮询失败的收口。 */
    private static final class SwitchableRunner implements CommandRunner {

        private final CommandRunner delegate;
        boolean failing;

        SwitchableRunner(CommandRunner delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecResult run(List<String> argv) {
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return delegate.run(argv);
        }

        @Override
        public void runInheritIo(List<String> argv) {
            delegate.runInheritIo(argv);
        }

        @Override
        public RunningCommand start(List<String> argv) {
            return delegate.start(argv);
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
        return openPackage(runner);
    }

    private ServicePackage openPackage(CommandRunner commandRunner) throws InvalidSpecException {
        return ServicePackage.open(pkg, commandRunner, probe,
                new SystemdTarget(pkg.resolve("units"), pkg.resolve("env")));
    }

    private TuiApp app(RecordingDisplay display, KeySource keys) throws InvalidSpecException {
        return app(display, keys, new FakeLogStream());
    }

    private TuiApp app(RecordingDisplay display, KeySource keys, FakeLogStream logs)
            throws InvalidSpecException {
        return new TuiApp(openPackage(), display, keys, FIXED_CLOCK, new Supplier<LogStream>() {
            @Override
            public LogStream get() {
                return logs;
            }
        });
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
        assertTrue(display.line(3).contains("active"), display.line(3));
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

    @Test
    void runReturnsZeroAfterQuit() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        assertEquals(0, app.run());
        assertFalse(app.running());
        assertEquals(1, display.shows());
    }

    @Test
    void runtimeFailureFromDisplayIsContainedAndReturnsOne() throws Exception {
        UncheckedIOException failure = new UncheckedIOException(
                "failed to draw TUI frame", new IOException("broken pipe"));
        FailingDisplay display = new FailingDisplay(failure);
        TuiApp app = new TuiApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)), FIXED_CLOCK,
                new Supplier<LogStream>() {
                    @Override
                    public LogStream get() {
                        return new FakeLogStream();
                    }
                });

        int code = assertDoesNotThrow(() -> app.run());

        assertEquals(1, code);
        assertFalse(app.running());
        assertTrue(app.statusNote().startsWith("tui failed: "), app.statusNote());
        assertTrue(app.statusNote().contains("failed to draw TUI frame"), app.statusNote());
        assertTrue(display.shows <= 1, "shows=" + display.shows);
    }

    @Test
    void statusPollsEveryTwoSeconds() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource());
        assertEquals(1, runner.calls().size(), "constructor loads one snapshot");

        app.tick(0);
        assertEquals(1, runner.calls().size());
        app.tick(1999);
        assertEquals(1, runner.calls().size());
        app.tick(2000);
        assertEquals(2, runner.calls().size());
        app.tick(4001);
        assertEquals(3, runner.calls().size());
    }

    @Test
    void clockSteppingBackwardsDoesNotFreezePolling() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource());
        app.tick(4000);
        assertEquals(2, runner.calls().size());

        // NTP 把墙钟往回拨：立即补一次并重建基线，而不是等到时间追上来。
        app.tick(1000);
        assertEquals(3, runner.calls().size());
        app.tick(2999);
        assertEquals(3, runner.calls().size());
        app.tick(3000);
        assertEquals(4, runner.calls().size());
    }

    @Test
    void partiallyStartedLogSourceIsClosedWhenStartFails() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.throwOnStart = true;

        TuiApp app = app(display, new ScriptedKeySource(), logs);
        assertTrue(app.awaitLogStart(5000), "log thread did not finish start()");

        assertTrue(logs.closed, "failed start must not leak the log stream");
        assertTrue(app.statusNote().contains("logs unavailable: boom"),
                "degraded notice should carry the cause: " + app.statusNote());
    }

    @Test
    void pollingPausesWhileOverlayOpen() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource());

        app.tick(2000);
        assertEquals(2, runner.calls().size());

        app.setOverlayOpen(true);
        app.tick(9000);
        assertEquals(2, runner.calls().size(), "overlay open must pause polling");

        app.setOverlayOpen(false);
        app.tick(9001);
        assertEquals(3, runner.calls().size(), "closing the overlay resumes polling");
    }

    @Test
    void pollFailureMarksRowsUnknownAndRecovers() throws Exception {
        SwitchableRunner switchable = new SwitchableRunner(runner);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(openPackage(switchable), display,
                new ScriptedKeySource(), FIXED_CLOCK, new Supplier<LogStream>() {
                    @Override
                    public LogStream get() {
                        return new FakeLogStream();
                    }
                });
        switchable.failing = true;

        assertTrue(app.tick(2000));
        assertTrue(app.running(), "a failed poll must not stop the UI");
        assertTrue(display.line(1).contains("unknown"), display.line(1));
        assertTrue(display.line(3).contains("status query failed: boom"), display.line(3));

        switchable.failing = false;
        app.tick(4000);
        assertFalse(display.line(1).contains("unknown"), display.line(1));
        assertTrue(display.line(3).contains("active"), display.line(3));
    }

    @Test
    void logsPanelShowsTailAndFollowHeader() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        TuiApp app = app(display, new ScriptedKeySource(), logs);
        logs.awaitStarted();
        assertEquals(200, logs.requestedLines);
        logs.emit("line 1");
        logs.emit("line 2");

        assertTrue(app.tick(0));

        assertTrue(display.line(5).contains("logs"), display.line(5));
        assertTrue(display.line(5).contains("journald"), display.line(5));
        assertTrue(display.line(5).contains("follow"), display.line(5));
        assertTrue(display.line(6).contains("line 1"), display.line(6));
        assertTrue(display.line(7).contains("line 2"), display.line(7));
        assertTrue(display.line(6).startsWith("│"), display.line(6));
        assertTrue(display.line(6).endsWith("│"), display.line(6));
    }

    @Test
    void newLinesRepaintTailWhileFollowing() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 10);
        FakeLogStream logs = new FakeLogStream();
        TuiApp app = app(display, new QueuedKeySource(KeyEvent.character('l')), logs);
        logs.awaitStarted();
        for (int i = 1; i <= 5; i++) {
            logs.emit("line " + i);
        }
        app.tick(0);
        assertTrue(display.line(6).contains("line 4"), display.line(6));

        logs.emit("line 6");
        app.tick(0);

        assertTrue(display.line(6).contains("line 5"), display.line(6));
        assertTrue(display.line(7).contains("line 6"), display.line(7));
    }

    @Test
    void scrollingPausesFollowAndBottomKeyResumesIt() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 10);
        FakeLogStream logs = new FakeLogStream();
        QueuedKeySource keys = new QueuedKeySource(KeyEvent.character('l'));
        TuiApp app = app(display, keys, logs);
        logs.awaitStarted();
        for (int i = 1; i <= 5; i++) {
            logs.emit("line " + i);
        }
        app.tick(0);
        assertTrue(display.line(5).contains("follow"), display.line(5));

        keys.enqueue(KeyEvent.character('k'));
        app.tick(0);
        assertTrue(display.line(5).contains("paused"), display.line(5));
        assertTrue(display.line(6).contains("line 3"), display.line(6));

        int shows = display.shows();
        logs.emit("line 6");
        app.tick(0);
        assertEquals(shows, display.shows(), "paused log pane must not chase new lines");

        keys.enqueue(KeyEvent.character('G'));
        app.tick(0);
        assertTrue(display.line(5).contains("follow"), display.line(5));
        assertTrue(display.line(7).contains("line 6"), display.line(7));
    }

    @Test
    void scrollingKeysMoveWindowWithinOneTick() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 10);
        FakeLogStream logs = new FakeLogStream();
        TuiApp app = app(display, new ScriptedKeySource(
                KeyEvent.character('l'), KeyEvent.character('k'), KeyEvent.character('k'),
                KeyEvent.of(NavKey.QUIT)), logs);
        logs.awaitStarted();
        for (int i = 1; i <= 5; i++) {
            logs.emit("line " + i);
        }

        assertFalse(app.tick(0));

        assertTrue(display.line(5).contains("paused"), display.line(5));
        assertTrue(display.line(6).contains("line 2"), display.line(6));
        assertTrue(display.line(7).contains("line 3"), display.line(7));
    }

    @Test
    void stderrLineBecomesNotice() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        TuiApp app = app(display, new ScriptedKeySource(), logs);
        logs.awaitStarted();
        logs.emitError("journal unavailable");

        app.tick(0);

        assertTrue(display.line(22).contains("notice: journal unavailable"), display.line(22));
    }

    @Test
    void followToggleChangesHeader() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.character('L')));

        assertTrue(app.tick(0));

        assertTrue(display.line(5).contains("paused"), display.line(5));
    }

    @Test
    void logsPanelHiddenOnShortScreenByDefault() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 19);
        TuiApp app = app(display, new ScriptedKeySource());

        app.tick(0);

        assertFalse(display.line(5).contains("logs"), display.line(5));
    }

    @Test
    void logsPanelToggleRevealsOnShortScreen() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 19);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.character('l')));

        app.tick(0);

        assertTrue(display.line(5).contains("logs"), display.line(5));
    }

    @Test
    void closeIsCalledWhenTheLoopExits() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)), logs);
        logs.awaitStarted();

        assertEquals(0, app.run());

        assertTrue(logs.closed, "LogStream.close() must run on exit");
    }

    @Test
    void unavailableLogSourceIsContained() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(openPackage(), display, new ScriptedKeySource(),
                FIXED_CLOCK, new Supplier<LogStream>() {
                    @Override
                    public LogStream get() {
                        throw new LogSourceException("log file not found: /nope");
                    }
                });

        assertTrue(app.tick(0));

        assertTrue(display.line(22).contains("logs unavailable: log file not found"),
                display.line(22));
    }
}
