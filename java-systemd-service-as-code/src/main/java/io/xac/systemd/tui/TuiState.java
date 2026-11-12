package io.xac.systemd.tui;

import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.core.StatusReport;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.systemd.UnitNaming;

/**
 * 一帧的不可变视图模型：TuiApp 只读它，状态线程刷新时整体替换，
 * 不在 UI 线程之外触碰任何部件（design §6.4）。
 */
public final class TuiState {

    /** 标题里的服务名，例如 {@code xac.demo}。 */
    public final String service;
    /** running / stopped / failed / unknown。 */
    public final String activity;
    /** 配置期望的自启状态，不是 systemd 里的实际值（实际值见状态区）。 */
    public final boolean autoStart;
    public final boolean root;
    /** unit / env / process / links 四行状态摘要。 */
    public final List<String> statusRows;

    public TuiState(String service, String activity, boolean autoStart, boolean root,
                    List<String> statusRows) {
        this.service = service;
        this.activity = activity;
        this.autoStart = autoStart;
        this.root = root;
        this.statusRows = statusRows;
    }

    public static TuiState load(ServicePackage servicePackage) {
        StatusReport report = servicePackage.status();
        ServiceSpec spec = servicePackage.spec();
        StatusResult status = report.status;
        return new TuiState(shortName(UnitNaming.unitName(spec)), activity(status),
                status.wantEnabled, servicePackage.isRoot(), statusRows(status));
    }

    private static String shortName(String unitName) {
        String suffix = ".service";
        return unitName.endsWith(suffix)
                ? unitName.substring(0, unitName.length() - suffix.length())
                : unitName;
    }

    private static String activity(StatusResult status) {
        if (!status.queryOk) {
            return "unknown";
        }
        String active = status.show.get("ActiveState");
        if (active == null || active.isEmpty()) {
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

    private static List<String> statusRows(StatusResult status) {
        List<String> rows = new ArrayList<String>(4);
        rows.add(row("unit", unitState(status), status.unitPath.toString()));
        rows.add(row("env", envState(status), envDetail(status)));
        rows.add(row("process", activity(status), processDetail(status)));
        rows.add(row("links", linkState(status), linkDetail(status)));
        return rows;
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
        return status.envConfigured ? status.envPath.toString() : "";
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

    private static String row(String label, String state, String detail) {
        StringBuilder row = new StringBuilder();
        row.append(pad(label, 8)).append(pad(state, 16));
        if (detail != null) {
            row.append(detail);
        }
        return row.toString();
    }

    private static String pad(String value, int width) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
