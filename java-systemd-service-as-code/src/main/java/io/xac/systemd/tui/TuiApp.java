package io.xac.systemd.tui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.xac.systemd.apply.ApplyOutcome;
import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.StepResult;
import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.core.StatusReport;
import io.xac.systemd.logs.LogSink;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.spec.Comments;
import io.xac.systemd.spec.YamlCommentScanner;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.tui.actions.ActionKind;
import io.xac.systemd.tui.actions.ActionOverlay;
import io.xac.systemd.tui.edit.EditScreen;
import io.xac.systemd.tui.edit.EditorModel;
import io.xac.systemd.tui.edit.FileStamp;

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
    private static final long STATUS_TIMEOUT_MILLIS = 3000;
    private static final long CLOSE_WAIT_MILLIS = 250;
    private static final int LOG_LINES = 200;
    private static final int LOG_CAPACITY = 2000;
    private static final int WIDE_SCREEN = 80;
    /** 屏幕至少 20 行才默认显示日志区，否则让位给状态区（design §6.1）。 */
    private static final int SHORT_SCREEN = 20;
    /** 标题 1 + 状态 4 + 日志头 1 + notice 1 + 键栏 1 = 8 行固定开销。 */
    private static final int FIXED_ROWS = 8;
    private static final String FULL_KEY_BAR =
            "a apply  r restart  d destroy  e edit  ? help  q quit";
    private static final String NARROW_KEY_BAR = "? help";
    /**
     * 放弃等待后的 notice：本进程随 TUI 退出，daemon 动作线程会被一起杀掉，
     * 所以只有当前这条外部命令可能自行跑完，剩余步骤不会再执行（design §6.6）。
     */
    private static final String ABANDON_NOTICE =
            "left running; current step may finish, remaining steps will not; re-run apply/destroy";

    /** 日志源工厂：显式带走 follow 参数，测试可断言 TUI 请求的是跟随模式。 */
    interface LogStreamFactory {
        LogStream open(int lines, boolean follow);
    }

    /** 重新打开包目录的接缝；测试注入假的 runner/probe，生产走 {@link ServicePackage#open(Path)}。 */
    interface PackageOpener {
        ServicePackage open(Path packageDir) throws InvalidSpecException;
    }

    private static final PackageOpener DEFAULT_OPENER = new PackageOpener() {
        @Override
        public ServicePackage open(Path packageDir) throws InvalidSpecException {
            return ServicePackage.open(packageDir);
        }
    };

    private ServicePackage servicePackage;
    private final Display display;
    private final KeySource keys;
    private final Clock clock;
    private final LogStreamFactory logStreams;
    private final PackageOpener opener;
    private LogPane logPane = new LogPane(LOG_CAPACITY);
    /** 日志线程完成 start()（成功或失败收口）后放行；测试用它替代 sleep。 */
    private final CountDownLatch logStart = new CountDownLatch(1);
    /** 单线程 daemon 执行器：状态查询永远不占用 UI 线程。 */
    private final ExecutorService statusExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "xac-tui-status");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TuiState state;
    private volatile LogStream logStream;
    private long lastStatusAt;
    private int logPage = 10;
    private boolean logsVisible = true;
    private boolean logsToggled;
    private boolean overlayOpen;
    /** 当前动作确认面板；null 表示主屏。 */
    private ActionOverlay overlay;
    /** 编辑屏覆盖层；非 null 时独占按键与画面。 */
    private EditScreen editor;
    /** 关闭覆盖层后强制补一次状态查询，不必等 2s 节流。 */
    private volatile boolean statusPollDue;
    /** 动作执行线程；非 null 表示后台还有一个动作在跑。 */
    private volatile Thread actionThread;
    private volatile boolean actionRunning;
    /** 用户放弃等待动作：后台线程完成时只丢弃结果，不再回写 UI（design §6.6）。 */
    private volatile boolean abandoned;
    private volatile ActionKind actionKind;
    private volatile String actionResult;
    private volatile boolean logsEnded;

    private volatile boolean running = true;
    private volatile boolean failed;
    private volatile boolean dirty = true;
    private volatile String notice = "";

    public TuiApp(ServicePackage servicePackage, Display display, KeySource keys) {
        this(servicePackage, display, keys, Clock.systemUTC());
    }

    public TuiApp(ServicePackage servicePackage, Display display, KeySource keys, Clock clock) {
        this(servicePackage, display, keys, clock, null);
    }

    /** 测试接缝：时间与日志源工厂都可注入，绝不启动真实终端。 */
    TuiApp(ServicePackage servicePackage, Display display, KeySource keys, Clock clock,
           LogStreamFactory logStreams) {
        this(servicePackage, display, keys, clock, logStreams, null);
    }

    /** 测试接缝：再加一个重新打开包目录的注入点，用于保存后的 reopen 流程。 */
    TuiApp(ServicePackage servicePackage, Display display, KeySource keys, Clock clock,
           LogStreamFactory logStreams, PackageOpener opener) {
        this.servicePackage = servicePackage;
        this.display = display;
        this.keys = keys;
        this.clock = clock;
        this.logStreams = logStreams != null ? logStreams : dynamicLogs();
        this.opener = opener != null ? opener : DEFAULT_OPENER;
        this.state = loadStateSafely();
        this.lastStatusAt = clock.millis();
        this.logsVisible = display.size().height >= SHORT_SCREEN;
        this.logPage = Math.max(1, logRows(display.size().height));
        startLogs();
    }

    /**
     * 生产用的日志源：延迟到 open() 时才读 {@code servicePackage}，
     * 这样保存后换了包实例，日志仍然读新配置。
     */
    private LogStreamFactory dynamicLogs() {
        return new LogStreamFactory() {
            @Override
            public LogStream open(int lines, boolean follow) {
                return TuiApp.this.servicePackage.logs(lines, follow);
            }
        };
    }

    /**
     * 主循环。终端读写失败都在这里收口：写一条 notice、停止循环、返回 1，
     * 绝不把异常抛给 picocli。返回 0 表示用户正常退出。
     *
     * <p>退出时在 finally 里关闭日志源与状态执行器。日志 close() 放在 daemon 线程，
     * UI 最多等 {@value #CLOSE_WAIT_MILLIS}ms，不把子进程的退出延迟算到画面上。
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
                    long nowMillis = clock.millis();
                    completeAction(nowMillis);
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
            }
        } finally {
            close();
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
            completeAction(nowMillis);
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
        // 动作执行期间用固定文案占住 notice 行，避免旧的 notice 被误读成执行结果。
        return actionRunning ? "running..." : notice;
    }

    /**
     * 关闭日志源并停掉状态执行器；可重复调用，供只用 {@link #tick(long)} 的调用方收尾。
     * 日志 close() 在 daemon 线程执行，UI 最多等 {@value #CLOSE_WAIT_MILLIS}ms。
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        closeLogs();
        statusExecutor.shutdownNow();
    }

    /**
     * 覆盖层（帮助/动作确认）打开时暂停状态轮询，关闭后下一次 tick 立即补一次。
     * Task 9 的 ACTION 覆盖层通过这个接缝接入。
     */
    void setOverlayOpen(boolean open) {
        if (this.overlayOpen && !open) {
            // 关闭覆盖层后立即补一次状态，保证面板期间错过的变化马上可见（design §6.4）。
            statusPollDue = true;
        }
        this.overlayOpen = open;
    }

    /** 测试接缝：等后台动作线程收尾；返回 false 表示超时。 */
    boolean awaitAction(long millis) throws InterruptedException {
        Thread thread = actionThread;
        if (thread == null) {
            return true;
        }
        thread.join(millis);
        return !thread.isAlive();
    }

    /**
     * 测试接缝：等日志线程跑完 start() 的同步段（成功返回或失败收口）。
     * 返回 false 表示超时；生产代码不调用它。
     */
    boolean awaitLogStart(long millis) throws InterruptedException {
        return logStart.await(millis, TimeUnit.MILLISECONDS);
    }

    private TuiState loadStateSafely() {
        Future<StatusReport> future;
        try {
            future = statusExecutor.submit(new Callable<StatusReport>() {
                @Override
                public StatusReport call() {
                    return servicePackage.status();
                }
            });
        } catch (RejectedExecutionException e) {
            return TuiState.unknown(servicePackage, "status executor closed");
        }
        try {
            // 状态查询有 3s 预算：UI 线程最多等这么久，超时就取消并显示 unknown。
            return TuiState.from(servicePackage, future.get(STATUS_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return TuiState.unknown(servicePackage, "status timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return TuiState.unknown(servicePackage, "status interrupted");
        } catch (ExecutionException e) {
            return TuiState.unknown(servicePackage, messageOf(e.getCause()));
        } catch (RuntimeException e) {
            future.cancel(true);
            return TuiState.unknown(servicePackage, messageOf(e));
        }
    }

    private void pollStatus(long nowMillis) {
        // 放弃等待后进程马上退出：退出这一 tick 不再发状态查询，
        // 否则可能为一条即将消失的结果阻塞 STATUS_TIMEOUT_MILLIS（N2）。
        if (overlayOpen || actionRunning || abandoned) {
            return;
        }
        if (!statusPollDue) {
            long elapsed = nowMillis - lastStatusAt;
            if (elapsed >= 0 && elapsed < STATUS_POLL_MILLIS) {
                return;
            }
        }
        statusPollDue = false;
        // elapsed < 0 说明墙钟被往回拨：立即补一次并重建基线，不能冻结到时间追上来。
        lastStatusAt = nowMillis;
        state = loadStateSafely();
        dirty = true;
    }

    private void startLogs() {
        LogStream stream;
        try {
            stream = logStreams.open(LOG_LINES, true);
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
            // start() 正常返回 = 日志流结束（EOF 或读取线程收口）。
            // 不能再假装还在 follow，否则表头会永远骗人。
            if (logStream == stream) {
                logsEnded = true;
                notice = "log stream ended";
                dirty = true;
            }
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
        final LogStream stream = logStream;
        logStream = null;
        if (stream == null) {
            return;
        }
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                closeQuietly(stream);
            }
        }, "xac-tui-close-logs");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void renderIfDirty() {
        if (dirty) {
            // 先清标记再画：render() 期间日志线程的新写入不会被这次清掉。
            dirty = false;
            render();
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
        if (editor != null) {
            handleEditor(event);
            return;
        }
        if (actionRunning) {
            // 动作执行期间忽略其它按键，但 q/esc/ctrl-c 可以放弃等待退出：
            // UI 立即退出，进程随即结束，daemon 动作线程也会被杀（design §6.6）。
            if (isAbandonKey(event)) {
                abandonAction();
            }
            return;
        }
        if (overlay != null) {
            handleOverlay(event);
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
        } else if (event.ch == 'a') {
            openApply();
        } else if (event.ch == 'r') {
            openRestart();
        } else if (event.ch == 'd') {
            openDestroy();
        } else if (event.ch == 'e') {
            openEditor();
        } else if (event.ch == '?') {
            notice = "help is not implemented yet";
            dirty = true;
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

    // --------------------------------------------------------------- editing

    /**
     * 打开编辑屏：读当前 xac.yaml 的文本与注释。模型必须来自磁盘上最新的 spec，
     * 不能拿 TUI 启动时的快照配新指纹——否则外部先改文件、再按 e/s 就会把别人的修改
     * 静默覆盖。语法坏到连 spec 都建不起来的包在这里拒绝进入编辑器（design §7.5）。
     */
    private void openEditor() {
        if (editor != null) {
            return;
        }
        Path packageDir = servicePackage.packageDir();
        Path file = packageDir.resolve("xac.yaml");
        try {
            ServicePackage fresh = opener.open(packageDir);
            // 先取指纹再读文本：读完到保存之间任何外部改动都能被发现。
            FileStamp stamp = FileStamp.of(file);
            String text = readText(file);
            Comments comments = YamlCommentScanner.scan(text);
            EditorModel model = EditorModel.from(fresh.spec(), comments);
            editor = new EditScreen(model, file, stamp);
            setOverlayOpen(true);
            notice = "";
            dirty = true;
        } catch (InvalidSpecException e) {
            notice = "cannot edit: " + firstViolation(e);
            dirty = true;
        } catch (IOException | RuntimeException e) {
            notice = "cannot edit: " + messageOf(e);
            dirty = true;
        }
    }

    private void handleEditor(KeyEvent event) {
        EditScreen current = editor;
        if (current == null) {
            return;
        }
        current.handle(event.key, event.ch);
        if (!current.closed()) {
            return;
        }
        boolean wasSaved = current.saved();
        List<String> notes = current.saveNote();
        editor = null;
        setOverlayOpen(false);
        dirty = true;
        if (wasSaved) {
            reopenAfterSave(notes);
        }
    }

    /** 保存成功后重新打开包，让 spec/状态/日志都基于新文件；失败也要把原因说清楚。 */
    private void reopenAfterSave(List<String> notes) {
        Path packageDir = servicePackage.packageDir();
        String savedNotice = "saved" + noteSuffix(notes);
        try {
            servicePackage = opener.open(packageDir);
            state = loadStateSafely();
            lastStatusAt = clock.millis();
            statusPollDue = false;
            closeLogs();
            logPane = new LogPane(LOG_CAPACITY);
            logsEnded = false;
            startLogs();
            notice = savedNotice;
        } catch (InvalidSpecException e) {
            notice = savedNotice + "; reopen failed: " + firstViolation(e);
        } catch (RuntimeException e) {
            notice = savedNotice + "; reopen failed: " + messageOf(e);
        }
        dirty = true;
    }

    private static String noteSuffix(List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return "";
        }
        StringBuilder suffix = new StringBuilder("; ");
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) {
                suffix.append("; ");
            }
            suffix.append(notes.get(i));
        }
        return suffix.toString();
    }

    private static String readText(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 覆盖层内只认 y（确认）与 enter/n/esc（取消）；其它键一律不生效。 */
    private void handleOverlay(KeyEvent event) {
        ActionOverlay current = overlay;
        if (current == null) {
            return;
        }
        if (isConfirmKey(event)) {
            if (current.accepts(NavKey.YES)) {
                confirmCurrent();
            }
            // 非 root：y 不生效，面板保持打开，让 "requires root" 一直可见。
            return;
        }
        if (isCancelKey(event)) {
            closeOverlay();
        }
    }

    private static boolean isConfirmKey(KeyEvent event) {
        if (event.key == NavKey.YES) {
            return true;
        }
        return event.key == NavKey.CHAR && (event.ch == 'y' || event.ch == 'Y');
    }

    private static boolean isCancelKey(KeyEvent event) {
        if (event.key == NavKey.ESC || event.key == NavKey.ENTER
                || event.key == NavKey.NO || event.key == NavKey.QUIT) {
            return true;
        }
        return event.key == NavKey.CHAR && (event.ch == 'n' || event.ch == 'N');
    }

    /** 动作执行期间的退出键：{@code q}、{@code esc} 与 Ctrl-C（映射成 {@link NavKey#QUIT}）。 */
    private static boolean isAbandonKey(KeyEvent event) {
        if (event.key == NavKey.QUIT || event.key == NavKey.ESC) {
            return true;
        }
        return event.key == NavKey.CHAR && event.ch == 'q';
    }

    private void openApply() {
        if (actionsDisabled()) {
            return;
        }
        if (!servicePackage.isRoot()) {
            openOverlay(ActionOverlay.rootRequired(ActionKind.APPLY));
            return;
        }
        openOverlay(ActionOverlay.applyPlan(servicePackage.plan()));
    }

    private void openRestart() {
        if (actionsDisabled()) {
            return;
        }
        if (!servicePackage.isRoot()) {
            openOverlay(ActionOverlay.rootRequired(ActionKind.RESTART));
            return;
        }
        openOverlay(ActionOverlay.restartConfirm(UnitNaming.unitName(servicePackage.spec())));
    }

    private void openDestroy() {
        if (actionsDisabled()) {
            return;
        }
        if (!servicePackage.isRoot()) {
            openOverlay(ActionOverlay.rootRequired(ActionKind.DESTROY));
            return;
        }
        try {
            openOverlay(ActionOverlay.destroyPlan(servicePackage.destroyPlan()));
        } catch (InvalidSpecException e) {
            notice = "destroy failed: " + firstViolation(e);
            dirty = true;
        }
    }

    /**
     * 状态查询失败时 systemd 不可达，动作键一律不开面板（design §6.5）。
     * 编辑与退出不受影响。
     */
    private boolean actionsDisabled() {
        if (state == null || state.queryOk) {
            return false;
        }
        notice = "actions disabled: systemd is not reachable";
        dirty = true;
        return true;
    }

    private void openOverlay(ActionOverlay actionOverlay) {
        this.overlay = actionOverlay;
        setOverlayOpen(true);
        dirty = true;
    }

    private void closeOverlay() {
        this.overlay = null;
        setOverlayOpen(false);
        dirty = true;
    }

    private void confirmCurrent() {
        ActionKind kind = overlay.kind();
        closeOverlay();
        startAction(kind);
    }

    /**
     * 动作放在独立 daemon 线程：UI 继续按 50ms 节奏取键/重绘，每 tick 只检查完成标志。
     * 动作结果只有执行线程写、UI 线程读，靠 volatile 发布。
     */
    private void startAction(ActionKind kind) {
        actionKind = kind;
        actionResult = null;
        abandoned = false;
        actionRunning = true;
        dirty = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = runAction(actionKind);
                } catch (RuntimeException e) {
                    result = actionKind.label() + " failed: " + messageOf(e);
                }
                if (abandoned) {
                    // UI 已经退出：动作结果无人接收，绝不回写界面（design §6.6）。
                    return;
                }
                actionResult = result;
                actionRunning = false;
                dirty = true;
            }
        }, "xac-tui-action");
        thread.setDaemon(true);
        actionThread = thread;
        thread.start();
    }

    /**
     * 用户按 q/esc/ctrl-c 放弃等待：UI 立即退出，进程随后结束，daemon 动作线程
     * 会被一起杀掉——只有正在执行的单条外部命令可能自行跑完，剩余步骤不会执行。
     * 完成后因为 {@code abandoned} 已置位，不会再写任何界面状态。
     */
    private void abandonAction() {
        abandoned = true;
        actionRunning = false;
        notice = ABANDON_NOTICE;
        running = false;
        // 不再补查状态：这一 tick 的 pollStatus 也会因 abandoned 直接返回（N2）。
        statusPollDue = false;
        dirty = true;
    }

    /** UI 线程收口：动作完成后写 notice、立即刷新状态。 */
    private void completeAction(long nowMillis) {
        if (abandoned || actionRunning || actionResult == null) {
            return;
        }
        String result = actionResult;
        actionResult = null;
        actionThread = null;
        notice = result;
        lastStatusAt = nowMillis;
        // 刚刚取过状态，本 tick 不必再因「覆盖层关闭补查」查第二次（M1）。
        statusPollDue = false;
        state = loadStateSafely();
        dirty = true;
    }

    private String runAction(ActionKind kind) {
        try {
            switch (kind) {
                case APPLY:
                    ApplyOutcome applied = servicePackage.apply(false);
                    if (applied.failed != null) {
                        return "apply failed: " + stepMessage(applied.failed);
                    }
                    return withWarnings("apply ok", applied.steps);
                case RESTART:
                    StepResult restarted = servicePackage.restart();
                    if (!restarted.ok) {
                        return "restart failed: " + stepMessage(restarted);
                    }
                    return "restart ok";
                case DESTROY:
                    DestroyOutcome destroyed = servicePackage.destroy(false);
                    if (destroyed.failed != null) {
                        return "destroy failed: " + stepMessage(destroyed.failed);
                    }
                    return withWarnings("destroy ok", destroyed.steps);
                default:
                    return kind.label() + " failed: unsupported action";
            }
        } catch (InvalidSpecException e) {
            return kind.label() + " failed: " + firstViolation(e);
        }
    }

    private static String withWarnings(String ok, List<StepResult> steps) {
        int warnings = 0;
        for (StepResult step : steps) {
            if (step.ok && step.message != null && !step.message.isEmpty()) {
                warnings++;
            }
        }
        return warnings == 0 ? ok : ok + " (" + warnings + " warning(s))";
    }

    private static String firstViolation(InvalidSpecException e) {
        if (!e.violations.isEmpty()) {
            return e.violations.get(0).toString();
        }
        return messageOf(e);
    }

    private static String stepMessage(StepResult step) {
        return step.message == null || step.message.isEmpty() ? "unknown error" : step.message;
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
        if (editor != null) {
            paintEditor(canvas);
            return;
        }
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

        if (actionRunning) {
            paintFrameRow(canvas, noticeRow, "running...");
        } else if (!notice.isEmpty() && height >= 4) {
            paintFrameRow(canvas, noticeRow, "notice: " + notice);
        }
        if (overlay != null) {
            paintOverlay(canvas);
        }
        paintKeyBar(canvas);
    }

    /**
     * 编辑屏占满整个画面：顶行标题，下面两列缩进画字段/行编辑/错误行。
     * 每行由 {@link EditScreen#lines} 自己按宽度截断，这里只负责摆放。
     */
    private void paintEditor(Canvas canvas) {
        EditScreen current = editor;
        if (current == null) {
            return;
        }
        int width = canvas.width();
        int height = canvas.height();
        canvas.clear();
        String service = state == null ? "xac" : state.service;
        canvas.text(0, 0, "edit " + service);
        List<String> lines = current.lines(Math.max(1, width - 2), Math.max(1, height - 1));
        int row = 1;
        for (String line : lines) {
            if (row >= height) {
                break;
            }
            canvas.text(1, row, line);
            row++;
        }
    }

    /**
     * 居中画确认面板：标题 + 内容行 + 底部提示。先把面板区域抹空，
     * 避免底下的状态/日志文字从内容行之间透出来。
     */
    private void paintOverlay(Canvas canvas) {
        int width = canvas.width();
        int height = canvas.height();
        // 少于 8 行时面板放不下正文（最多只剩提示行），宁可不画，避免
        // 出现只有 "y destroy" 却没有内容的确认面板（N3）。
        if (width < 8 || height < 8) {
            return;
        }
        List<String> lines = overlay.lines();
        String title = overlay.kind().label();
        String hint = overlay.hint();
        int inner = Math.max(title.length(), hint.length());
        for (String line : lines) {
            inner = Math.max(inner, line.length());
        }
        if (lines.size() > height - 6) {
            // 溢出提示可能比正文更长，先按最坏情况留宽，避免提示本身被裁掉。
            inner = Math.max(inner, ("... (" + lines.size() + " more lines)").length());
        }
        int boxWidth = Math.min(width - 2, inner + 4);
        int boxHeight = Math.min(height - 2, lines.size() + 4);
        int left = Math.max(0, (width - boxWidth) / 2);
        int top = Math.max(0, (height - boxHeight) / 2);
        for (int y = top; y < top + boxHeight; y++) {
            canvas.hline(y, ' ');
        }
        canvas.box(left, top, boxWidth, boxHeight);
        int textX = Math.min(left + 2, width - 1);
        canvas.text(textX, top + 1, title);
        int contentTop = top + 2;
        int contentBottom = top + boxHeight - 3;
        int capacity = contentBottom - contentTop + 1;
        List<String> visible = visibleLines(lines, capacity);
        int row = contentTop;
        for (String line : visible) {
            if (row > contentBottom) {
                break;
            }
            canvas.text(textX, row++, line);
        }
        if (visible.size() < lines.size() && capacity > 0) {
            // 纵向溢出：最后一行固定报出被省略的行数，warning 行优先保留（M2）。
            canvas.text(textX, contentBottom,
                    "... (" + (lines.size() - visible.size()) + " more lines)");
        }
        canvas.text(textX, top + boxHeight - 2, hint);
    }

    /**
     * 面板内容超出可用行数时，先留一行给溢出提示，再把溢出区里的 {@code warning:}
     * 行换进可见区：destroy 面板的归属警告是最不能丢的一行（M2）。
     */
    private static List<String> visibleLines(List<String> lines, int capacity) {
        if (capacity <= 0) {
            return Collections.emptyList();
        }
        if (lines.size() <= capacity) {
            return lines;
        }
        int slots = capacity - 1;
        List<String> tailWarnings = new ArrayList<String>();
        for (int i = slots; i < lines.size() && tailWarnings.size() < slots; i++) {
            String line = lines.get(i);
            if (isWarningLine(line)) {
                tailWarnings.add(line);
            }
        }
        int head = Math.max(0, slots - tailWarnings.size());
        List<String> visible = new ArrayList<String>(capacity);
        for (int i = 0; i < head && i < lines.size(); i++) {
            visible.add(lines.get(i));
        }
        visible.addAll(tailWarnings);
        return visible;
    }

    private static boolean isWarningLine(String line) {
        return line != null && line.startsWith("warning:");
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
        if (logsEnded) {
            status = status + " (ended)";
        }
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
