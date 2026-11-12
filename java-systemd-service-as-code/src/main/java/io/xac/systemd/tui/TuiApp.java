package io.xac.systemd.tui;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.logs.LogSink;
import io.xac.systemd.logs.LogStream;

/**
 * TUI 外壳。只依赖 {@link Display} 与 {@link KeySource}，因此测试不用启动终端；
 * 真实终端实现在 {@link LanternaDisplay} / {@link LanternaKeySource}。
 *
 * <p>线程模型（design §6.4）：UI 线程每 {@value #POLL_MILLIS}ms 取键、按需重绘；
 * 状态轮询在本线程的 tick 里按 {@value #STATUS_POLL_MILLIS}ms 节流，测试直接喂
 * 时间戳，不 sleep；日志读取线程由 {@link LogStream} 自己拥有，回调只做
 * “入缓冲 + 置脏标记”两件事。
 */
public final class TuiApp {

    private static final long POLL_MILLIS = 50;
    private static final long STATUS_POLL_MILLIS = 2000;
    private static final int LOG_LINES = 200;
    private static final int LOG_CAPACITY = 2000;
    private static final int WIDE_SCREEN = 80;
    /** 屏幕至少 20 行才默认显示日志区，否则让位给状态区（design §6.1）。 */
    private static final int SHORT_SCREEN = 20;
    /** 标题 1 + 状态 4 + 日志头 1 + notice 1 + 键栏 1 = 8 行固定开销。 */
    private static final int FIXED_ROWS = 8;
    private static final String FULL_KEY_BAR =
            "q quit  a apply  r restart  d destroy  e edit  ? help";
    private static final String NARROW_KEY_BAR = "? help";

    private final ServicePackage servicePackage;
    private final Display display;
    private final KeySource keys;
    private final Clock clock;
    private final Supplier<LogStream> logStreams;
    private final LogPane logPane = new LogPane(LOG_CAPACITY);
    /** 日志线程完成 start()（成功或失败收口）后放行；测试用它替代 sleep。 */
    private final CountDownLatch logStart = new CountDownLatch(1);

    private TuiState state;
    private LogStream logStream;
    private long lastStatusAt;
    private int logPage = 10;
    private boolean logsVisible = true;
    private boolean logsToggled;
    private boolean overlayOpen;

    private volatile boolean running = true;
    private volatile boolean failed;
    private volatile boolean dirty = true;
    private volatile String notice = "";

    public TuiApp(ServicePackage servicePackage, Display display, KeySource keys) {
        this(servicePackage, display, keys, Clock.systemUTC());
    }

    public TuiApp(ServicePackage servicePackage, Display display, KeySource keys, Clock clock) {
        this(servicePackage, display, keys, clock, packageLogs(servicePackage));
    }

    /** 测试接缝：时间与日志源都可注入，绝不启动真实终端。 */
    TuiApp(ServicePackage servicePackage, Display display, KeySource keys, Clock clock,
           Supplier<LogStream> logStreams) {
        this.servicePackage = servicePackage;
        this.display = display;
        this.keys = keys;
        this.clock = clock;
        this.logStreams = logStreams;
        this.state = loadStateSafely();
        this.lastStatusAt = clock.millis();
        this.logsVisible = display.size().height >= SHORT_SCREEN;
        this.logPage = Math.max(1, logRows(display.size().height));
        startLogs();
    }

    private static Supplier<LogStream> packageLogs(final ServicePackage servicePackage) {
        return new Supplier<LogStream>() {
            @Override
            public LogStream get() {
                return servicePackage.logs(LOG_LINES);
            }
        };
    }

    /**
     * 主循环。终端读写失败都在这里收口：写一条 notice、停止循环、返回 1，
     * 绝不把异常抛给 picocli。返回 0 表示用户正常退出。
     *
     * <p>退出时在 finally 里关闭日志源。设计文档提醒 close() 可能要等子进程，
     * 按约定仍在这里关：最后一帧已经画完，阻塞只影响退出延迟，不影响画面一致性。
     */
    public int run() {
        try {
            while (running) {
                try {
                    KeyEvent event = keys.next(POLL_MILLIS);
                    if (event != null) {
                        handle(event);
                        dirty = true;
                    }
                    pollStatus(clock.millis());
                    renderIfDirty();
                } catch (IOException e) {
                    notice = "input error: " + messageOf(e);
                    running = false;
                    dirty = true;
                    renderFinalNotice();
                } catch (RuntimeException e) {
                    fail(e);
                }
            }
        } finally {
            closeLogs();
        }
        return failed ? 1 : 0;
    }

    /**
     * 处理一批待处理按键并画一帧；返回是否继续运行。
     * 即使这一批里按了退出键，也要先把最后一帧画出来。
     */
    public boolean tick() {
        return tick(clock.millis());
    }

    /** 测试接缝：显式传入当前毫秒数，驱动状态轮询节流与重绘。 */
    public boolean tick(long nowMillis) {
        if (!running) {
            return false;
        }
        try {
            for (KeyEvent event = keys.next(0); event != null; event = keys.next(0)) {
                handle(event);
                dirty = true;
            }
            pollStatus(nowMillis);
            renderIfDirty();
        } catch (IOException e) {
            notice = "input error: " + messageOf(e);
            running = false;
            dirty = true;
            renderFinalNotice();
        } catch (RuntimeException e) {
            fail(e);
        }
        return running;
    }

    public boolean running() {
        return running;
    }

    public String statusNote() {
        return notice;
    }

    /**
     * 覆盖层（帮助/动作确认）打开时暂停状态轮询，关闭后下一次 tick 立即补一次。
     * Task 9 的 ACTION 覆盖层通过这个接缝接入。
     */
    void setOverlayOpen(boolean open) {
        this.overlayOpen = open;
    }

    /**
     * 测试接缝：等日志线程跑完 start() 的同步段（成功返回或失败收口）。
     * 返回 false 表示超时；生产代码不调用它。
     */
    boolean awaitLogStart(long millis) throws InterruptedException {
        return logStart.await(millis, TimeUnit.MILLISECONDS);
    }

    private TuiState loadStateSafely() {
        try {
            return TuiState.load(servicePackage);
        } catch (RuntimeException e) {
            // 轮询失败只降级这一帧，UI 继续可退出（I-1 异常收口）。
            return TuiState.unknown(servicePackage, messageOf(e));
        }
    }

    private void pollStatus(long nowMillis) {
        if (overlayOpen) {
            return;
        }
        long elapsed = nowMillis - lastStatusAt;
        if (elapsed >= 0 && elapsed < STATUS_POLL_MILLIS) {
            return;
        }
        // elapsed < 0 说明墙钟被往回拨：立即补一次并重建基线，不能冻结到时间追上来。
        lastStatusAt = nowMillis;
        state = loadStateSafely();
        dirty = true;
    }

    private void startLogs() {
        LogStream stream;
        try {
            stream = logStreams.get();
        } catch (RuntimeException e) {
            logStart.countDown();
            degradeLogs(e);
            return;
        }
        this.logStream = stream;
        // 日志源自己的读取线程可能阻塞在 start() 里（design §6.4），
        // 因此 start() 放在 daemon 线程上，绝不让构造/首帧等它。
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpLogs(stream);
            }
        }, "xac-tui-logs");
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpLogs(LogStream stream) {
        try {
            stream.start(LOG_LINES, new LogSink() {
                @Override
                public void accept(String line) {
                    logPane.append(line);
                    if (logPane.following()) {
                        dirty = true;
                    }
                }
            }, new LogSink() {
                @Override
                public void accept(String line) {
                    if (line != null && !line.isEmpty()) {
                        notice = line;
                        dirty = true;
                    }
                }
            });
        } catch (RuntimeException e) {
            // 日志文件缺失/不可读不该拖垮 TUI：降级成一条持久 notice。
            // start 可能已经起了半截线程/子进程，尽力关掉再放手。
            closeQuietly(stream);
            if (logStream == stream) {
                logStream = null;
                degradeLogs(e);
            }
        } finally {
            logStart.countDown();
        }
    }

    private void degradeLogs(RuntimeException e) {
        notice = "logs unavailable: " + messageOf(e);
        dirty = true;
    }

    private static void closeQuietly(LogStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // 关闭失败不影响降级路径。
        }
    }

    private void closeLogs() {
        LogStream stream = logStream;
        logStream = null;
        closeQuietly(stream);
    }

    private void renderIfDirty() {
        if (dirty) {
            render();
            dirty = false;
        }
    }

    /**
     * 输入已经失败时，尽力把带 notice 的最后一帧画出来；画不出来就按终端故障收口。
     */
    private void renderFinalNotice() {
        try {
            renderIfDirty();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException e) {
        failed = true;
        running = false;
        dirty = false;
        notice = "tui failed: " + messageOf(e);
    }

    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private void handle(KeyEvent event) {
        if (event == null) {
            return;
        }
        if (event.key == NavKey.QUIT || event.key == NavKey.ESC) {
            running = false;
            return;
        }
        if (event.key == NavKey.REFRESH) {
            refresh();
            return;
        }
        if (event.key == NavKey.UP) {
            logPane.scrollUp(1);
            return;
        }
        if (event.key == NavKey.DOWN) {
            logPane.scrollDown(1);
            return;
        }
        if (event.key == NavKey.PGUP) {
            logPane.scrollUp(logPage);
            return;
        }
        if (event.key == NavKey.PGDN) {
            logPane.scrollDown(logPage);
            return;
        }
        if (event.key == NavKey.TOP) {
            logPane.toTop();
            return;
        }
        if (event.key == NavKey.BOTTOM) {
            logPane.toBottom();
            return;
        }
        if (event.key == NavKey.FOLLOW) {
            logPane.toggleFollow();
            return;
        }
        if (event.key != NavKey.CHAR) {
            return;
        }
        if (event.ch == 'q') {
            running = false;
        } else if (event.ch == 'R') {
            refresh();
        } else if (event.ch == 'l') {
            logsToggled = true;
            logsVisible = !logsVisible;
        } else if (event.ch == 'L') {
            logPane.toggleFollow();
        } else if (event.ch == 'j') {
            logPane.scrollDown(1);
        } else if (event.ch == 'k') {
            logPane.scrollUp(1);
        } else if (event.ch == 'g') {
            logPane.toTop();
        } else if (event.ch == 'G') {
            logPane.toBottom();
        }
    }

    private void refresh() {
        state = loadStateSafely();
        lastStatusAt = clock.millis();
        notice = "refreshed";
        dirty = true;
    }

    private void render() {
        Display.Size size = display.size();
        Canvas canvas = new Canvas(Math.max(1, size.width), Math.max(1, size.height));
        paint(canvas);
        display.show(canvas);
    }

    private void paint(Canvas canvas) {
        int height = canvas.height();
        if (!logsToggled) {
            logsVisible = height >= SHORT_SCREEN;
        }
        paintTitle(canvas);

        int noticeRow = height - 2;
        int row = 1;
        for (String statusRow : state.statusRows) {
            if (row > noticeRow) {
                break;
            }
            paintFrameRow(canvas, row++, statusRow);
        }

        int logHeight = logRows(height);
        if (logsVisible && logHeight > 0 && row <= noticeRow) {
            logPage = Math.max(1, logHeight);
            paintLogsHeader(canvas, row++);
            List<String> lines = logPane.visible(logHeight);
            for (String line : lines) {
                if (row > noticeRow) {
                    break;
                }
                paintFrameRow(canvas, row++, line);
            }
        }

        if (!notice.isEmpty() && height >= 4) {
            paintFrameRow(canvas, noticeRow, "notice: " + notice);
        }
        paintKeyBar(canvas);
    }

    private static int logRows(int height) {
        return Math.max(0, height - FIXED_ROWS);
    }

    private void paintTitle(Canvas canvas) {
        int width = canvas.width();
        String left = "┌ " + state.service + " ";
        String right = " " + state.activity + " · auto-start " + (state.autoStart ? "on" : "off")
                + " · " + (state.root ? "root" : "not root") + " ┐";
        int fill = width - left.length() - right.length();
        if (fill >= 0) {
            canvas.text(0, 0, left);
            canvas.text(left.length(), 0, repeat('─', fill));
            canvas.text(left.length() + fill, 0, right);
            return;
        }
        // 窄屏：状态信息让位，只保留服务名。
        canvas.text(0, 0, left);
        int dashes = width - left.length() - 1;
        if (dashes > 0) {
            canvas.text(left.length(), 0, repeat('─', dashes));
        }
        canvas.put(width - 1, 0, '┐');
    }

    /**
     * 画一行带左右边框的内容：先写字再补边框，让 Canvas 按显示宽度裁剪；
     * 绝不用 substring 按 char 下标截断（CJK 会切出半个宽字符）。
     */
    private void paintFrameRow(Canvas canvas, int y, String content) {
        int width = canvas.width();
        if (width < 2) {
            canvas.text(0, y, content == null ? "" : content);
            return;
        }
        if (content != null && !content.isEmpty()) {
            canvas.text(2, y, content);
        }
        canvas.put(0, y, '│');
        canvas.put(width - 1, y, '│');
    }

    private void paintLogsHeader(Canvas canvas, int y) {
        int width = canvas.width();
        if (width < 2) {
            return;
        }
        String status = logPane.following() ? "follow" : "paused";
        String left = "├ logs ─ " + state.logSource + " · " + status + " ";
        if (left.length() + 1 <= width) {
            canvas.text(0, y, left);
            canvas.text(left.length(), y, repeat('─', width - left.length() - 1));
        } else {
            canvas.text(0, y, left);
        }
        canvas.put(width - 1, y, '┤');
    }

    private void paintKeyBar(Canvas canvas) {
        int width = canvas.width();
        int y = canvas.height() - 1;
        String keyBar = width >= WIDE_SCREEN ? FULL_KEY_BAR : NARROW_KEY_BAR;
        String left = "└ " + keyBar + " ";
        int fill = width - left.length() - 1;
        if (fill >= 0) {
            canvas.text(0, y, left);
            canvas.text(left.length(), y, repeat('─', fill));
        } else {
            canvas.hline(y, '─');
            canvas.put(0, y, '└');
        }
        canvas.put(width - 1, y, '┘');
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
