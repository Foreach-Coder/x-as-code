package io.xac.systemd.tui;

import java.util.List;

import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.core.StatusReport;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.systemd.UnitNaming;

/**
 * 一帧的不可变视图模型：TuiApp 只读它，状态轮询时整体替换，
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
    /** unit / env / process / links 四行状态摘要，行首带漂移标记。 */
    public final List<String> statusRows;
    /** 日志区标题里的来源：配置文件路径或 {@code journald}。 */
    public final String logSource;

    public TuiState(String service, String activity, boolean autoStart, boolean root,
                    List<String> statusRows) {
        this(service, activity, autoStart, root, statusRows, "journald");
    }

    public TuiState(String service, String activity, boolean autoStart, boolean root,
                    List<String> statusRows, String logSource) {
        this.service = service;
        this.activity = activity;
        this.autoStart = autoStart;
        this.root = root;
        this.statusRows = statusRows;
        this.logSource = logSource;
    }

    public static TuiState load(ServicePackage servicePackage) {
        StatusReport report = servicePackage.status();
        ServiceSpec spec = servicePackage.spec();
        StatusResult status = report.status;
        return new TuiState(shortName(UnitNaming.unitName(spec)), StatusView.activity(status),
                status.wantEnabled, servicePackage.isRoot(), StatusView.lines(report),
                logSource(spec));
    }

    /**
     * 状态查询抛异常时的一帧：保留服务名与 root 信息，四行全部 unknown。
     * 轮询失败不杀 UI（design §6.5 / I-1 的异常收口风格）。
     */
    public static TuiState unknown(ServicePackage servicePackage, String reason) {
        ServiceSpec spec = servicePackage.spec();
        boolean autoStart = spec.lifecycle == null || spec.lifecycle.autoStartOrDefault();
        return new TuiState(shortName(UnitNaming.unitName(spec)), "unknown", autoStart,
                servicePackage.isRoot(), StatusView.unknown(reason), logSource(spec));
    }

    private static String shortName(String unitName) {
        String suffix = ".service";
        return unitName.endsWith(suffix)
                ? unitName.substring(0, unitName.length() - suffix.length())
                : unitName;
    }

    private static String logSource(ServiceSpec spec) {
        String logFile = spec.logFile;
        return logFile == null || logFile.trim().isEmpty() ? "journald" : logFile;
    }
}
