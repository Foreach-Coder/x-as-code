package io.xac.systemd.tui;

import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.core.StatusReport;
import io.xac.systemd.status.StatusResult;

/**
 * 把 {@link StatusReport} 压成状态区的四行文本：unit / env / process / links。
 *
 * <p>行首两列是漂移标记：{@code "! "} 表示这一行与配置期望不一致（与
 * {@link StatusResult#drift()} 同一判断口径），{@code "  "} 表示正常。
 * 文字不含宽度裁剪与边框，绘制交给 {@link TuiApp} 与 {@link Canvas}，
 * 这样 CJK 路径不会在视图层被按 char 截断。
 */
public final class StatusView {

    private static final int LABEL_WIDTH = 8;
    private static final int STATE_WIDTH = 16;

    private StatusView() {
    }

    /** 四行状态摘要，顺序固定：unit、env、process、links。 */
    public static List<String> lines(StatusReport report) {
        StatusResult status = report.status;
        List<String> rows = new ArrayList<String>(4);
        rows.add(row("unit", unitState(status), text(status.unitPath), !status.unitInSync));
        rows.add(row("env", envState(status), envDetail(status), envDrift(status)));
        rows.add(row("process", processState(status), processDetail(status), processDrift(status)));
        rows.add(row("links", linkState(status), linkDetail(status),
                !status.linkProblems.isEmpty()));
        return rows;
    }

    /**
     * 状态查询本身失败时的四行：全部标记 unknown，进程行带上失败原因。
     * UI 继续可用，不因为查询失败退出（design §6.5）。
     */
    public static List<String> unknown(String reason) {
        String why = "status query failed: " + (reason == null ? "" : reason);
        List<String> rows = new ArrayList<String>(4);
        rows.add(row("unit", "unknown", "systemctl show failed", true));
        rows.add(row("env", "unknown", "systemctl show failed", true));
        rows.add(row("process", "unknown", why, true));
        rows.add(row("links", "unknown", "systemctl show failed", true));
        return rows;
    }

    /** 标题栏用的活动状态：running / stopped / <raw> / unknown。 */
    public static String activity(StatusResult status) {
        String active = activeState(status);
        if (active.isEmpty()) {
            return "unknown";
        }
        if ("active".equals(active)) {
            return "running";
        }
        if ("inactive".equals(active)) {
            return "stopped";
        }
        return active;
    }

    private static boolean processDrift(StatusResult status) {
        if (!status.queryOk) {
            return true;
        }
        String active = activeState(status);
        return !active.isEmpty() && !"active".equals(active);
    }

    private static boolean envDrift(StatusResult status) {
        return status.envConfigured && status.envReadable && !status.envInSync;
    }

    private static String unitState(StatusResult status) {
        if (status.unitInSync) {
            return "in sync";
        }
        return status.unitInstalled ? "out of sync" : "missing";
    }

    private static String envState(StatusResult status) {
        if (!status.envConfigured) {
            return "not configured";
        }
        if (!status.envReadable) {
            return "cannot check";
        }
        if (status.envInSync) {
            return "in sync";
        }
        return status.envInstalled ? "out of sync" : "missing";
    }

    private static String envDetail(StatusResult status) {
        return status.envConfigured ? text(status.envPath) : "";
    }

    private static String processState(StatusResult status) {
        if (!status.queryOk) {
            return "unknown";
        }
        String active = activeState(status);
        return active.isEmpty() ? "unknown" : active;
    }

    private static String processDetail(StatusResult status) {
        if (!status.queryOk) {
            return "systemctl show failed";
        }
        String pid = status.show.get("MainPID");
        if (pid == null || pid.isEmpty() || "0".equals(pid)) {
            return "";
        }
        return "pid " + pid;
    }

    private static String linkState(StatusResult status) {
        return status.linkProblems.isEmpty() ? "ok" : "problems";
    }

    private static String linkDetail(StatusResult status) {
        int problems = status.linkProblems.size();
        return problems == 0 ? "" : problems + " problem(s)";
    }

    private static String activeState(StatusResult status) {
        String active = status.show.get("ActiveState");
        return active == null ? "" : active;
    }

    private static String row(String label, String state, String detail, boolean drift) {
        StringBuilder row = new StringBuilder();
        row.append(drift ? "! " : "  ");
        row.append(pad(label, LABEL_WIDTH)).append(pad(state, STATE_WIDTH));
        if (detail != null) {
            row.append(detail);
        }
        return row.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String pad(String value, int width) {
        StringBuilder padded = new StringBuilder(value == null ? "" : value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
