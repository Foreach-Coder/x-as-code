package io.xac.systemd.tui;

import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.exec.RunningCommand;
import io.xac.systemd.logs.LogSink;
import io.xac.systemd.logs.LogSourceException;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        final CountDownLatch closeStarted = new CountDownLatch(1);
        volatile boolean blockUntilClosed;
        volatile boolean throwOnStart;
        volatile boolean closed;
        volatile boolean openedWithFollow;
        volatile long closeDelayMillis;
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
            return openedWithFollow;
        }

        @Override
        public void close() {
            closed = true;
            closeStarted.countDown();
            closedLatch.countDown();
            long delay = closeDelayMillis;
            if (delay > 0L) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    /** 阻塞状态查询，直到被中断或 10 秒超时，用来验证 UI 不等待状态。 */
    private static final class BlockingRunner implements CommandRunner {

        private final CommandRunner delegate;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean blocking;

        BlockingRunner(CommandRunner delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecResult run(List<String> argv) {
            if (blocking) {
                entered.countDown();
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("status interrupted");
                }
                return new ExecResult(0, "", "");
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

    private TuiApp app(RecordingDisplay display, KeySource keys, final FakeLogStream logs)
            throws InvalidSpecException {
        return new TuiApp(openPackage(), display, keys, FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        logs.openedWithFollow = follow;
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
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
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
                new ScriptedKeySource(), FIXED_CLOCK, new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
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
        assertTrue(logs.openedWithFollow, "TUI must request follow=true from the log source");
        assertTrue(logs.isFollow(), "the log stream itself must be in follow mode");
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
                FIXED_CLOCK, new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        throw new LogSourceException("log file not found: /nope");
                    }
                });

        assertTrue(app.tick(0));

        assertTrue(display.line(22).contains("logs unavailable: log file not found"),
                display.line(22));
    }

    @Test
    void endedLogStreamShowsNoticeAndStopsPretendingToFollow() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        TuiApp app = app(display, new ScriptedKeySource(), logs);
        logs.awaitStarted();
        assertTrue(app.awaitLogStart(1000), "log pump must finish before asserting the ended state");

        assertTrue(app.tick(0));

        assertTrue(app.statusNote().contains("log stream ended"),
                "ended stream must be visible: " + app.statusNote());
        assertTrue(display.line(5).contains("follow (ended)"), display.line(5));
    }

    @Test
    void closeDoesNotBlockTheUiThread() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        logs.closeDelayMillis = 5000L;
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)), logs);
        logs.awaitStarted();

        long started = System.nanoTime();
        assertEquals(0, app.run());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 1000L, "run() blocked " + elapsedMillis + "ms in close()");
        assertTrue(logs.closeStarted.await(1, TimeUnit.SECONDS), "close() was not called");
    }

    @Test
    void statusQueryTimeoutKeepsTheUiResponsive() throws Exception {
        BlockingRunner blocking = new BlockingRunner(runner);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(openPackage(blocking), display, new ScriptedKeySource(),
                FIXED_CLOCK, new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        return new FakeLogStream();
                    }
                });
        blocking.blocking = true;

        long started = System.nanoTime();
        assertTrue(app.tick(2000));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 3500L, "tick() blocked " + elapsedMillis + "ms on status");
        assertTrue(display.line(1).contains("unknown"), display.line(1));
        assertTrue(display.line(3).contains("status timed out"), display.line(3));

        blocking.blocking = false;
        app.tick(4001);
        assertFalse(display.line(1).contains("unknown"), display.line(1));

        app.close();
    }

    /** 用来在测试里模拟「动作执行到一半」，验证 UI 不被阻塞、动作键在执行期间被忽略。 */
    private static final class BlockingActionRunner implements CommandRunner {

        private final CommandRunner delegate;
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingActionRunner(CommandRunner delegate) {
            this.delegate = delegate;
        }

        void release() {
            release.countDown();
        }

        @Override
        public ExecResult run(List<String> argv) {
            if (argv.size() >= 2 && "systemctl".equals(argv.get(0))
                    && "restart".equals(argv.get(1))) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("restart interrupted");
                }
            }
            return delegate.run(argv);
        }

        @Override
        public RunningCommand start(List<String> argv) {
            return delegate.start(argv);
        }

        @Override
        public void runInheritIo(List<String> argv) {
            delegate.runInheritIo(argv);
        }
    }

    /** 动作测试只需要一个能写出 artifact.jar 的普通服务包。 */
    private void prepareActionPackage() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        Files.createDirectories(pkg.resolve("units"));
        Files.createDirectories(pkg.resolve("env"));
        runner.addResultForCommand("systemctl",
                new ExecResult(0, "LoadState=not-found\nFragmentPath=\n", ""));
    }

    private Path unitPath() {
        return pkg.resolve("units").resolve("xac.demo.service");
    }

    private Path envPath() {
        return pkg.resolve("env").resolve("xac.demo.env");
    }

    private TuiApp actionApp(ServicePackage opened, RecordingDisplay display, KeySource keys,
            FakeLogStream logs) {
        return new TuiApp(opened, display, keys, FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        logs.openedWithFollow = follow;
                        return logs;
                    }
                });
    }

    private static String screen(RecordingDisplay display) {
        Canvas canvas = display.last();
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            out.append(canvas.line(y)).append('\n');
        }
        return out.toString();
    }

    private static boolean hasCall(RecordingRunner runner, String... argv) {
        return countCalls(runner, argv) > 0;
    }

    private static int countCalls(RecordingRunner runner, String... argv) {
        int count = 0;
        for (List<String> call : runner.calls()) {
            if (call.equals(Arrays.asList(argv))) {
                count++;
            }
        }
        return count;
    }

    @Test
    void applyPreviewListsThePlanWithoutExecuting() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('a')), logs);
        int before = runner.calls().size();

        assertTrue(app.tick(0));

        String screen = screen(display);
        assertTrue(screen.contains("would create path-links: []"), screen);
        assertTrue(screen.contains("would systemctl restart xac.demo.service"), screen);
        assertEquals(before, runner.calls().size(), "preview must not execute anything");
    }

    @Test
    void applyRunsOnceAfterY() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        assertFalse(hasCall(runner, "systemctl", "restart", "xac.demo.service"));
        assertTrue(screen(display).contains("would create path-links: []"));

        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(app.awaitAction(5000), "apply did not finish");
        assertTrue(app.tick(0));

        assertTrue(app.statusNote().startsWith("apply ok"), app.statusNote());
        assertTrue(Files.isRegularFile(unitPath()), "apply must write the unit");
        assertEquals(1, countCalls(runner, "systemctl", "restart", "xac.demo.service"));
    }

    @Test
    void enterCancelsApplyPreviewWithoutExecuting() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('a'), KeyEvent.of(NavKey.ENTER)), logs);

        assertTrue(app.tick(0));

        // 先确认面板已关闭，再等动作线程收口（如果有的话），最后才断言“没执行”。
        assertFalse(screen(display).contains("would create path-links"), screen(display));
        assertTrue(app.awaitAction(5000), "enter must not start an action");
        assertEquals(0, countCalls(runner, "systemctl", "restart", "xac.demo.service"),
                "enter must not execute apply");
        assertFalse(Files.exists(unitPath()));
    }

    /** 非 root 时 a/r/d 都只开面板；y 不能绕过 root 检查，也不能留下任何写入。 */
    @Test
    void nonRootActionsAreBlocked() throws Exception {
        prepareActionPackage();
        probe.root = false;
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("apply requires root"), screen(display));

        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));

        // 面板必须仍然开着，“requires root”一直可见，动作一次都没跑。
        assertTrue(screen(display).contains("requires root"), screen(display));
        assertEquals(0, countCalls(runner, "systemctl", "restart", "xac.demo.service"),
                "non-root must not run apply");
        assertFalse(Files.exists(unitPath()));
        assertFalse(Files.exists(envPath()));
    }

    @Test
    void nonRootRestartAndDestroyAreBlocked() throws Exception {
        prepareActionPackage();
        probe.root = false;
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('r'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("restart requires root"), screen(display));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("requires root"), screen(display));
        assertEquals(0, countCalls(runner, "systemctl", "restart", "xac.demo.service"));

        keys.enqueue(KeyEvent.of(NavKey.ESC));
        assertTrue(app.tick(0));
        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("destroy requires root"), screen(display));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("requires root"), screen(display));
        assertEquals(0, countCalls(runner, "systemctl", "stop", "xac.demo.service"));
        assertEquals(0, countCalls(runner, "systemctl", "disable", "xac.demo.service"));
    }

    @Test
    void editKeyOpensEditor() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('e')), logs);

        assertTrue(app.tick(0));

        String screen = screen(display);
        assertTrue(screen.contains("edit xac.demo"), screen);
        assertTrue(screen.contains("description"), screen);
        assertTrue(screen.contains("enter edit  space toggle  s save"), screen);
        assertFalse(app.statusNote().contains("not implemented"));
    }

    @Test
    void editSaveReopensPackageWithNewValue() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        final List<FakeLogStream> streams = new ArrayList<FakeLogStream>();
        final AtomicReference<ServicePackage> reopened = new AtomicReference<ServicePackage>();
        TuiApp app = new TuiApp(openPackage(), display,
                new ScriptedKeySource(
                        KeyEvent.character('e'),
                        KeyEvent.character('c'),
                        KeyEvent.of(NavKey.ENTER),
                        KeyEvent.character('c'),
                        KeyEvent.character('h'),
                        KeyEvent.character('a'),
                        KeyEvent.character('n'),
                        KeyEvent.character('g'),
                        KeyEvent.character('e'),
                        KeyEvent.character('d'),
                        KeyEvent.of(NavKey.ENTER),
                        KeyEvent.character('s')),
                FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        FakeLogStream stream = new FakeLogStream();
                        stream.blockUntilClosed = true;
                        streams.add(stream);
                        return stream;
                    }
                },
                new TuiApp.PackageOpener() {
                    @Override
                    public ServicePackage open(Path packageDir) throws InvalidSpecException {
                        ServicePackage opened = ServicePackage.open(packageDir, runner, probe,
                                new SystemdTarget(packageDir.resolve("units"),
                                        packageDir.resolve("env")));
                        reopened.set(opened);
                        return opened;
                    }
                });

        assertTrue(app.tick(0));

        assertNotNull(reopened.get(), "save must reopen the package");
        assertEquals("changed", reopened.get().spec().description);
        assertEquals("saved", app.statusNote());
        assertEquals(2, streams.size(), "log stream must be rebuilt after reopen");
        assertTrue(streams.get(0).closed, "old log stream must be closed");
    }

    @Test
    void editOpensWithSpecFreshlyReadFromDisk() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('e')),
                FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        FakeLogStream stream = new FakeLogStream();
                        stream.blockUntilClosed = true;
                        return stream;
                    }
                },
                new TuiApp.PackageOpener() {
                    @Override
                    public ServicePackage open(Path packageDir) throws InvalidSpecException {
                        return ServicePackage.open(packageDir, runner, probe,
                                new SystemdTarget(packageDir.resolve("units"),
                                        packageDir.resolve("env")));
                    }
                });
        // TUI 启动后有人先改了文件：编辑屏必须用重读的 spec，而不是启动时的快照。
        Path file = pkg.resolve("xac.yaml");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, text.replace("name: demo", "name: demo\ndescription: external text")
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(app.tick(0));

        String screen = screen(display);
        assertTrue(screen.contains("external text"), screen);
        assertFalse(screen.contains("cannot edit"), screen);
    }

    @Test
    void editSaveReportsDroppedCommentsInNotice() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        Path file = pkg.resolve("xac.yaml");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, (text + "\n# workdir: /opt/prod/work\n")
                .getBytes(StandardCharsets.UTF_8));
        TuiApp app = new TuiApp(openPackage(), display,
                new ScriptedKeySource(
                        KeyEvent.character('e'),
                        KeyEvent.character('c'),
                        KeyEvent.of(NavKey.ENTER),
                        KeyEvent.character('c'),
                        KeyEvent.character('h'),
                        KeyEvent.character('a'),
                        KeyEvent.character('n'),
                        KeyEvent.character('g'),
                        KeyEvent.character('e'),
                        KeyEvent.character('d'),
                        KeyEvent.of(NavKey.ENTER),
                        KeyEvent.character('s'),
                        KeyEvent.character('s')),
                FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        FakeLogStream stream = new FakeLogStream();
                        stream.blockUntilClosed = true;
                        return stream;
                    }
                },
                new TuiApp.PackageOpener() {
                    @Override
                    public ServicePackage open(Path packageDir) throws InvalidSpecException {
                        return ServicePackage.open(packageDir, runner, probe,
                                new SystemdTarget(packageDir.resolve("units"),
                                        packageDir.resolve("env")));
                    }
                });

        assertTrue(app.tick(0));

        assertTrue(app.statusNote().startsWith("saved"),
                app.statusNote());
        assertTrue(app.statusNote().contains("1 comment(s) were dropped"),
                app.statusNote());
        assertTrue(app.statusNote().contains("1 commented-out example"),
                app.statusNote());
        String noticeRow = display.line(22);
        assertTrue(noticeRow.contains("notice: saved"), noticeRow);
        assertTrue(noticeRow.contains("1 comment(s) were dropped"), noticeRow);
        assertTrue(noticeRow.contains("1 commented-out example"), noticeRow);
    }

    @Test
    void editOpensWhenSpecHasRuntimeValidationErrors() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = new TuiApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('e')),
                FIXED_CLOCK,
                new TuiApp.LogStreamFactory() {
                    @Override
                    public LogStream open(int lines, boolean follow) {
                        FakeLogStream stream = new FakeLogStream();
                        stream.blockUntilClosed = true;
                        return stream;
                    }
                },
                new TuiApp.PackageOpener() {
                    @Override
                    public ServicePackage open(Path packageDir) throws InvalidSpecException {
                        return ServicePackage.open(packageDir, runner, probe,
                                new SystemdTarget(packageDir.resolve("units"),
                                        packageDir.resolve("env")));
                    }
                });
        // ${MISSING} 是部署期校验错误：编辑器必须仍然进得去，用户才能补 env。
        Path file = pkg.resolve("xac.yaml");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, text.replace("    - -Xmx512m", "    - ${MISSING}")
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(app.tick(0));

        String screen = screen(display);
        assertTrue(screen.contains("${MISSING}"), screen);
        assertFalse(screen.contains("cannot edit"), screen);
    }

    @Test
    void keyBarListsActions() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        TuiApp app = app(display, new ScriptedKeySource(KeyEvent.of(NavKey.QUIT)));

        assertFalse(app.tick());

        String keyBar = display.line(23);
        assertTrue(keyBar.contains("a apply  r restart  d destroy  e edit  ? help  q quit"),
                keyBar);
    }

    @Test
    void restartRunsOnlyAfterConfirmationAndDoesNotWriteUnit() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('r'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("systemctl restart xac.demo.service"), screen(display));
        assertFalse(hasCall(runner, "systemctl", "restart", "xac.demo.service"));

        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(app.awaitAction(5000), "restart did not finish");
        assertTrue(app.tick(0));

        assertTrue(app.statusNote().startsWith("restart ok"), app.statusNote());
        assertEquals(1, countCalls(runner, "systemctl", "restart", "xac.demo.service"));
        assertFalse(Files.exists(unitPath()), "restart must not write the unit");
        assertFalse(Files.exists(envPath()), "restart must not write the env file");
    }

    @Test
    void destroyPreviewRequiresYAndRemovesUnit() throws Exception {
        prepareActionPackage();
        ServicePackage opened = openPackage();
        opened.apply(false);
        assertTrue(Files.isRegularFile(unitPath()), "setup must write the unit");
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(opened, display, keys, logs);

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));
        String panel = screen(display);
        assertTrue(panel.contains("will remove:"), panel);
        assertTrue(panel.contains("xac.dem"), panel);
        assertTrue(panel.contains("keep:"), panel);

        keys.enqueue(KeyEvent.of(NavKey.ENTER));
        assertTrue(app.tick(0));
        // 先断言面板关闭，再等动作线程收口，最后才检查“什么都没做”。
        assertFalse(screen(display).contains("will remove:"), screen(display));
        assertTrue(app.awaitAction(5000), "enter must not start destroy");
        assertTrue(Files.isRegularFile(unitPath()), "enter must not destroy the unit");
        assertEquals(0, countCalls(runner, "systemctl", "stop", "xac.demo.service"),
                "enter must not stop the service");
        assertEquals(0, countCalls(runner, "systemctl", "disable", "xac.demo.service"),
                "enter must not disable the service");

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(app.awaitAction(5000), "destroy did not finish");
        assertTrue(app.tick(0));

        assertTrue(app.statusNote().startsWith("destroy ok"), app.statusNote());
        assertFalse(Files.exists(unitPath()), "destroy must remove the unit");
    }

    @Test
    void actionRunsOffTheUiThread() throws Exception {
        prepareActionPackage();
        BlockingActionRunner blocking = new BlockingActionRunner(runner);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(blocking), display, keys, logs);

        keys.enqueue(KeyEvent.character('r'));
        assertTrue(app.tick(0));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(blocking.entered.await(5, TimeUnit.SECONDS), "restart did not start");

        // 执行期间按下动作键：被忽略，动作继续跑，UI 不阻塞。
        keys.enqueue(KeyEvent.character('a'));
        long started = System.nanoTime();
        assertTrue(app.tick(0));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 1000L, "tick() blocked " + elapsedMillis + "ms on restart");
        assertTrue(screen(display).contains("running..."), screen(display));
        assertTrue(app.running(), "non-quit keys must be ignored while an action runs");

        blocking.release();
        assertTrue(app.awaitAction(5000), "restart did not finish");
        assertTrue(app.tick(0));

        assertTrue(app.statusNote().startsWith("restart ok"), app.statusNote());
    }

    /** 动作卡在后台时，q 必须能退出 UI；动作线程完成后不能再回写界面。 */
    @Test
    void quitAbandonsRunningActionWithoutTouchingTheDisplay() throws Exception {
        assertAbandonsRunningAction(KeyEvent.character('q'));
    }

    /** ESC 与 q 走同一条放弃路径（N4）。 */
    @Test
    void escapeAbandonsRunningActionWithoutTouchingTheDisplay() throws Exception {
        assertAbandonsRunningAction(KeyEvent.of(NavKey.ESC));
    }

    /** Ctrl-C 在 LanternaKeySource 里映射成 NavKey.QUIT，也必须能放弃（N4）。 */
    @Test
    void ctrlCAbandonsRunningActionWithoutTouchingTheDisplay() throws Exception {
        assertAbandonsRunningAction(KeyEvent.of(NavKey.QUIT));
    }

    private void assertAbandonsRunningAction(KeyEvent abandonKey) throws Exception {
        prepareActionPackage();
        BlockingActionRunner blocking = new BlockingActionRunner(runner);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(blocking), display,
                new AbandonAfterActionSource(blocking, abandonKey), logs);

        int code = assertDoesNotThrow(new ThrowingSupplier<Integer>() {
            @Override
            public Integer get() {
                return app.run();
            }
        });

        assertEquals(0, code);
        assertFalse(app.running(), "abandon key must exit the UI even while an action is running");
        assertTrue(app.statusNote().contains("remaining steps will not"),
                app.statusNote());
        assertTrue(app.statusNote().contains("re-run apply/destroy"), app.statusNote());
        int showsAtExit = display.shows();

        blocking.release();
        assertTrue(app.awaitAction(5000), "background action did not finish");
        assertEquals(showsAtExit, display.shows(),
                "the action thread must not write the display after the UI exits");
    }

    /**
     * N2：动作中按 q 放弃等待时，退出这一 tick 不能再发状态查询。
     * 状态查询阻塞 10 秒；若退出前仍 poll，UI 会为一条即将消失的结果最多等 3 秒。
     */
    @Test
    void abandonDoesNotWaitForABlockedStatusQuery() throws Exception {
        prepareActionPackage();
        BlockingRunner status = new BlockingRunner(runner);
        BlockingActionRunner blocking = new BlockingActionRunner(status);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(blocking), display,
                new AbandonAfterActionSource(blocking, 'r', KeyEvent.character('q'),
                        new Runnable() {
                            @Override
                            public void run() {
                                status.blocking = true;
                            }
                        }), logs);

        long startedAt = System.nanoTime();
        int code = assertDoesNotThrow(new ThrowingSupplier<Integer>() {
            @Override
            public Integer get() {
                return app.run();
            }
        });
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals(0, code);
        assertFalse(app.running());
        assertEquals(1L, status.entered.getCount(),
                "abandon must not start a status query on the way out");
        assertTrue(elapsedMillis < 1500L,
                "abandon must not wait for a status query, took " + elapsedMillis + " ms");

        // 先解除阻塞再放行动作线程，避免它在后台 sleep 10 秒。
        status.blocking = false;
        blocking.release();
        assertTrue(app.awaitAction(5000), "background action did not finish");
    }

    /**
     * 先按动作键再 y 启动动作，等它真的阻塞在 restart 上，再吐放弃键；
     * 保证放弃路径被命中而不是动作已完成。
     */
    private static final class AbandonAfterActionSource implements KeySource {

        private final BlockingActionRunner runner;
        private final char actionKey;
        private final KeyEvent abandonKey;
        private final Runnable afterEntered;
        private int step;

        AbandonAfterActionSource(BlockingActionRunner runner, KeyEvent abandonKey) {
            this(runner, 'a', abandonKey, null);
        }

        AbandonAfterActionSource(BlockingActionRunner runner, char actionKey, KeyEvent abandonKey,
                Runnable afterEntered) {
            this.runner = runner;
            this.actionKey = actionKey;
            this.abandonKey = abandonKey;
            this.afterEntered = afterEntered;
        }

        @Override
        public KeyEvent next(long timeoutMillis) {
            step++;
            if (step == 1) {
                return KeyEvent.character(actionKey);
            }
            if (step == 2) {
                return KeyEvent.character('y');
            }
            if (step == 3) {
                try {
                    if (!runner.entered.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("action did not start");
                    }
                    if (afterEntered != null) {
                        afterEntered.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the action");
                }
                return abandonKey;
            }
            return null;
        }
    }

    @Test
    void escapeClosesOverlayInsteadOfQuitting() throws Exception {
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        TuiApp app = actionApp(openPackage(), display,
                new ScriptedKeySource(KeyEvent.character('a'), KeyEvent.of(NavKey.ESC)), logs);

        assertTrue(app.tick(0));

        assertTrue(app.running(), "escape must only close the overlay");
        assertFalse(screen(display).contains("would create path-links"), screen(display));
    }

    /** restart 面板的取消键：面板关闭、动作线程根本没启动、restart 一次没跑。 */
    private void assertRestartCancelledBy(KeyEvent cancel) throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('r'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("systemctl restart xac.demo.service"), screen(display));

        keys.enqueue(cancel);
        assertTrue(app.tick(0));

        assertFalse(screen(display).contains("systemctl restart xac.demo.service"),
                "cancel key must close the restart panel");
        assertTrue(app.awaitAction(5000), "cancel key must not start an action");
        assertEquals(0, countCalls(runner, "systemctl", "restart", "xac.demo.service"));
    }

    @Test
    void restartPanelCancelsOnNo() throws Exception {
        assertRestartCancelledBy(KeyEvent.character('n'));
    }

    @Test
    void restartPanelCancelsOnEscape() throws Exception {
        assertRestartCancelledBy(KeyEvent.of(NavKey.ESC));
    }

    @Test
    void restartPanelCancelsOnEnter() throws Exception {
        assertRestartCancelledBy(KeyEvent.of(NavKey.ENTER));
    }

    @Test
    void applyPanelCancelsOnNo() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("would create path-links"), screen(display));

        keys.enqueue(KeyEvent.character('n'));
        assertTrue(app.tick(0));

        assertFalse(screen(display).contains("would create path-links"), screen(display));
        assertTrue(app.awaitAction(5000), "n must not start apply");
        assertEquals(0, countCalls(runner, "systemctl", "restart", "xac.demo.service"));
        assertFalse(Files.exists(unitPath()));
    }

    @Test
    void destroyPanelCancelsOnNo() throws Exception {
        prepareActionPackage();
        ServicePackage opened = openPackage();
        opened.apply(false);
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(opened, display, keys, logs);

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));
        assertTrue(screen(display).contains("will remove:"), screen(display));

        keys.enqueue(KeyEvent.character('n'));
        assertTrue(app.tick(0));

        assertFalse(screen(display).contains("will remove:"), screen(display));
        assertTrue(app.awaitAction(5000), "n must not start destroy");
        assertTrue(Files.isRegularFile(unitPath()), "n must not remove the unit");
        assertEquals(0, countCalls(runner, "systemctl", "stop", "xac.demo.service"));
        assertEquals(0, countCalls(runner, "systemctl", "disable", "xac.demo.service"));
    }

    @Test
    void doubleYExecutesApplyOnlyOnce() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));

        assertTrue(app.awaitAction(5000), "apply did not finish");
        assertTrue(app.tick(0));

        assertEquals(1, countCalls(runner, "systemctl", "restart", "xac.demo.service"),
                "second y must not re-run apply");
    }

    /** design §6.5：systemctl 查询失败时动作键禁用；查询恢复后重新可用。 */
    @Test
    void statusQueryFailureDisablesActionKeysUntilRecovery() throws Exception {
        prepareActionPackage();
        SwitchableRunner switchable = new SwitchableRunner(runner);
        switchable.failing = true;
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(switchable), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        assertEquals("actions disabled: systemd is not reachable", app.statusNote());
        assertFalse(screen(display).contains("would create path-links"), screen(display));

        keys.enqueue(KeyEvent.character('r'));
        assertTrue(app.tick(0));
        assertFalse(screen(display).contains("systemctl restart xac.demo.service"),
                screen(display));

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));
        assertFalse(screen(display).contains("will remove:"), screen(display));

        switchable.failing = false;
        assertTrue(app.tick(2000), "recovery poll must succeed");
        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(2000));
        assertTrue(screen(display).contains("would create path-links"), screen(display));
    }

    /** M1：completeAction 刚取过状态，同一 tick 的 pollStatus 不能再查第二次。 */
    @Test
    void actionCompletionDoesNotPollStatusTwice() throws Exception {
        prepareActionPackage();
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('a'));
        assertTrue(app.tick(0));
        int before = countStatusQueries(runner);

        keys.enqueue(KeyEvent.character('y'));
        assertTrue(app.tick(0));
        assertTrue(app.awaitAction(5000), "apply did not finish");
        assertTrue(app.tick(0));

        // apply 的预检查一次、完成刷新查一次；没有 M1 时完成 tick 会再查一次。
        assertEquals(before + 2, countStatusQueries(runner),
                "completion refresh must replace, not duplicate, the poll");
    }

    private static int countStatusQueries(RecordingRunner runner) {
        int count = 0;
        for (List<String> argv : runner.calls()) {
            if (argv.size() >= 2 && "systemctl".equals(argv.get(0)) && "show".equals(argv.get(1))) {
                count++;
            }
        }
        return count;
    }

    /** M7：unit 存在但不是 xac 管理的普通文件时，destroy 面板必须给出 warning。 */
    @Test
    void destroyPanelShowsOwnershipWarning() throws Exception {
        prepareActionPackage();
        Files.write(unitPath(), "[Unit]\nDescription=foreign\n"
                .getBytes(StandardCharsets.UTF_8));
        RecordingDisplay display = new RecordingDisplay(80, 24);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));

        String panel = screen(display);
        assertTrue(panel.contains("warning: unit is not managed by xac"), panel);
        assertTrue(Files.isRegularFile(unitPath()), "preview must not remove anything");
    }

    /** M2：面板纵向溢出时最后一行报省略行数，且 warning 行优先保留。 */
    @Test
    void overlayOverflowKeepsWarningAndReportsHiddenLines() throws Exception {
        prepareActionPackage();
        Files.write(unitPath(), "[Unit]\nDescription=foreign\n"
                .getBytes(StandardCharsets.UTF_8));
        RecordingDisplay display = new RecordingDisplay(80, 8);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));

        String panel = screen(display);
        assertTrue(panel.contains("warning: unit is not managed by xac"), panel);
        assertTrue(panel.contains("... (3 more lines)"), panel);
    }

    /**
     * N3：80×7 放不下面板正文，只画一条 "y destroy" 提示会误导用户；
     * 这种情况下宁可不画面板，保留普通状态行。
     */
    @Test
    void tinyScreenDoesNotPaintDestroyPanelOrHint() throws Exception {
        prepareActionPackage();
        Files.write(unitPath(), "[Unit]\nDescription=foreign\n"
                .getBytes(StandardCharsets.UTF_8));
        RecordingDisplay display = new RecordingDisplay(80, 7);
        FakeLogStream logs = new FakeLogStream();
        logs.blockUntilClosed = true;
        QueuedKeySource keys = new QueuedKeySource();
        TuiApp app = actionApp(openPackage(), display, keys, logs);

        keys.enqueue(KeyEvent.character('d'));
        assertTrue(app.tick(0));

        String panel = screen(display);
        assertFalse(panel.contains("y destroy"), panel);
        assertFalse(panel.contains("will remove:"), panel);
        // 有面板时第 1 行会被面板边框的 "┌" 覆盖；没有面板时应仍是状态行的 "│"。
        assertTrue(display.line(1).startsWith("│"), display.line(1));
    }
}
