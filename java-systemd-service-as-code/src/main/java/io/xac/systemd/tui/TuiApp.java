package io.xac.systemd.tui;

import java.io.IOException;

import io.xac.systemd.core.ServicePackage;

/**
 * TUI 外壳。只依赖 {@link Display} 与 {@link KeySource}，因此测试不用启动终端；
 * 真实终端实现在 {@link LanternaDisplay} / {@link LanternaKeySource}。
 */
public final class TuiApp implements Runnable {

    private static final long POLL_MILLIS = 50;
    private static final int WIDE_SCREEN = 80;
    private static final String FULL_KEY_BAR =
            "q quit  a apply  r restart  d destroy  e edit  ? help";
    private static final String NARROW_KEY_BAR = "? help";

    private final ServicePackage servicePackage;
    private final Display display;
    private final KeySource keys;

    private TuiState state;
    private boolean running = true;
    private boolean dirty = true;
    private String notice = "";

    public TuiApp(ServicePackage servicePackage, Display display, KeySource keys) {
        this.servicePackage = servicePackage;
        this.display = display;
        this.keys = keys;
        this.state = TuiState.load(servicePackage);
    }

    @Override
    public void run() {
        while (running) {
            try {
                KeyEvent event = keys.next(POLL_MILLIS);
                if (event != null) {
                    handle(event);
                    dirty = true;
                }
            } catch (IOException e) {
                notice = "input error: " + e.getMessage();
                running = false;
                dirty = true;
            }
            if (dirty) {
                render();
                dirty = false;
            }
        }
    }

    /**
     * 处理一批待处理按键并画一帧；返回是否继续运行。
     * 即使这一批里按了退出键，也要先把最后一帧画出来。
     */
    public boolean tick() {
        if (!running) {
            return false;
        }
        try {
            for (KeyEvent event = keys.next(0); event != null; event = keys.next(0)) {
                handle(event);
                dirty = true;
            }
        } catch (IOException e) {
            notice = "input error: " + e.getMessage();
            running = false;
            dirty = true;
        }
        if (dirty) {
            render();
            dirty = false;
        }
        return running;
    }

    public boolean running() {
        return running;
    }

    public String statusNote() {
        return notice;
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
        if (event.key == NavKey.CHAR) {
            if (event.ch == 'q') {
                running = false;
            } else if (event.ch == 'R') {
                refresh();
            }
        }
    }

    private void refresh() {
        state = TuiState.load(servicePackage);
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
        paintTitle(canvas);

        int lastContentRow = height - 2;
        int row = 1;
        for (String statusRow : state.statusRows) {
            if (row > lastContentRow) {
                break;
            }
            paintFrameRow(canvas, row++, statusRow);
        }
        if (row <= lastContentRow) {
            paintLogsHeader(canvas, row++);
        }
        if (!notice.isEmpty() && height >= 4) {
            paintFrameRow(canvas, height - 2, "notice: " + notice);
        }
        paintKeyBar(canvas);
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

    private void paintFrameRow(Canvas canvas, int y, String content) {
        int width = canvas.width();
        if (width < 2) {
            canvas.text(0, y, content);
            return;
        }
        canvas.put(0, y, '│');
        canvas.put(width - 1, y, '│');
        int room = width - 4;
        if (room <= 0) {
            return;
        }
        String body = content == null ? "" : content;
        if (body.length() > room) {
            body = body.substring(0, room);
        }
        canvas.text(2, y, body + repeat(' ', room - body.length()));
    }

    private void paintLogsHeader(Canvas canvas, int y) {
        int width = canvas.width();
        if (width < 2) {
            return;
        }
        String prefix = "├ logs ";
        int fill = width - prefix.length() - 1;
        if (fill >= 0) {
            canvas.text(0, y, prefix);
            canvas.text(prefix.length(), y, repeat('─', fill));
        } else {
            canvas.hline(y, '─');
            canvas.put(0, y, '├');
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
