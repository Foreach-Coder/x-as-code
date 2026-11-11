package io.xac.systemd.status;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StatusResult {

    public final String service;
    public final Path packageDir;
    public final Path unitPath;
    public final boolean unitInstalled;
    public final boolean unitInSync;
    public final Path envPath;
    public final boolean envConfigured;
    public final boolean envInstalled;
    /** false 表示 env 文件存在但当前用户无权读取；无法校验不算漂移。 */
    public final boolean envReadable;
    public final boolean envInSync;
    public final Map<String, String> show;
    /** false 表示 systemctl show 查询失败；此时 show 为空，不能据此推断运行/自启状态。 */
    public final boolean queryOk;
    public final boolean enabled;
    public final boolean wantEnabled;
    public final List<String> linkProblems;

    public StatusResult(String service, Path packageDir, Path unitPath, boolean unitInstalled,
                        boolean unitInSync, Path envPath, boolean envConfigured, boolean envInstalled,
                        boolean envReadable, boolean envInSync, Map<String, String> show,
                        boolean queryOk, boolean enabled, boolean wantEnabled,
                        List<String> linkProblems) {
        this.service = service;
        this.packageDir = packageDir;
        this.unitPath = unitPath;
        this.unitInstalled = unitInstalled;
        this.unitInSync = unitInSync;
        this.envPath = envPath;
        this.envConfigured = envConfigured;
        this.envInstalled = envInstalled;
        this.envReadable = envReadable;
        this.envInSync = envInSync;
        this.show = show;
        this.queryOk = queryOk;
        this.enabled = enabled;
        this.wantEnabled = wantEnabled;
        this.linkProblems = linkProblems;
    }

    public List<String> drift() {
        List<String> drift = new ArrayList<String>();
        if (!unitInSync) {
            drift.add(unitInstalled
                    ? "unit out of sync: " + unitPath
                    : "unit not installed: " + unitPath);
        }
        if (!envInSync && envReadable) {
            if (!envConfigured) {
                drift.add("env file is extra (env is empty in config): " + envPath);
            } else if (envInstalled) {
                drift.add("env out of sync: " + envPath);
            } else {
                drift.add("env missing: " + envPath);
            }
        }
        if (queryOk) {
            String loadState = show.get("LoadState");
            String activeState = show.get("ActiveState");
            if ("not-found".equals(loadState) && unitInstalled) {
                // unit 文件在，systemd 却没有加载它——通常是忘了 daemon-reload。
                drift.add("service: not-found");
            } else if (activeState != null && !activeState.isEmpty()
                    && !"active".equals(activeState)) {
                String sub = show.get("SubState");
                drift.add("service: " + activeState
                        + (sub == null || sub.isEmpty() ? "" : " (" + sub + ")"));
            }
            if (enabled != wantEnabled) {
                drift.add(wantEnabled ? "auto-start disabled" : "auto-start enabled");
            }
        } else if (unitInstalled) {
            // 查询失败时不能把空 map 当成 disabled，只报告「没有观察到状态」。
            drift.add("systemd: cannot query " + unitPath.getFileName());
        }
        drift.addAll(linkProblems);
        return drift;
    }

    public boolean healthy() {
        return drift().isEmpty();
    }

    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("service: ").append(service).append('\n');
        out.append("package: ").append(packageDir).append('\n');
        out.append("unit: ").append(unitPath).append(unitState()).append('\n');
        if (!envConfigured) {
            out.append("env: (not configured)\n");
        } else if (!envReadable) {
            out.append("env: (cannot verify: root required)\n");
        } else {
            out.append("env: ").append(envPath)
                    .append(envInSync ? " (in sync)" : " (out of sync)").append('\n');
        }
        out.append("active: ").append(value("ActiveState", "unknown"));
        String sub = show.get("SubState");
        if (sub != null && !sub.isEmpty()) {
            out.append(" (").append(sub).append(')');
        }
        String since = show.get("ActiveEnterTimestamp");
        if (since != null && !since.isEmpty()) {
            out.append(" since ").append(since);
        }
        out.append('\n');
        out.append("pid: ").append(value("MainPID", "unknown")).append('\n');
        out.append("auto-start: ")
                .append(queryOk ? (enabled ? "enabled" : "disabled") : "unknown")
                .append('\n');
        for (String line : drift()) {
            out.append("drift: ").append(line).append('\n');
        }
        return out.toString();
    }

    private String unitState() {
        if (unitInSync) {
            return " (in sync)";
        }
        return unitInstalled ? " (out of sync)" : " (missing)";
    }

    private String value(String key, String fallback) {
        String value = show.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
