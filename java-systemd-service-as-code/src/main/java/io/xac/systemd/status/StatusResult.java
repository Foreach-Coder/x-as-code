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
    public final boolean envInSync;
    public final Map<String, String> show;
    public final boolean enabled;
    public final boolean wantEnabled;
    public final List<String> linkProblems;

    public StatusResult(String service, Path packageDir, Path unitPath, boolean unitInstalled,
                        boolean unitInSync, Path envPath, boolean envConfigured, boolean envInstalled,
                        boolean envInSync, Map<String, String> show, boolean enabled,
                        boolean wantEnabled, List<String> linkProblems) {
        this.service = service;
        this.packageDir = packageDir;
        this.unitPath = unitPath;
        this.unitInstalled = unitInstalled;
        this.unitInSync = unitInSync;
        this.envPath = envPath;
        this.envConfigured = envConfigured;
        this.envInstalled = envInstalled;
        this.envInSync = envInSync;
        this.show = show;
        this.enabled = enabled;
        this.wantEnabled = wantEnabled;
        this.linkProblems = linkProblems;
    }

    public List<String> drift() {
        List<String> drift = new ArrayList<String>();
        if (!unitInSync) {
            drift.add(unitInstalled
                    ? "unit 文件与配置不一致: " + unitPath
                    : "unit 未安装: " + unitPath);
        }
        if (!envInSync) {
            if (!envConfigured) {
                drift.add("env 文件多余（配置中已无 env）: " + envPath);
            } else if (envInstalled) {
                drift.add("env 文件与配置不一致: " + envPath);
            } else {
                drift.add("env 文件不存在: " + envPath);
            }
        }
        if (enabled != wantEnabled) {
            drift.add(wantEnabled ? "未设置开机自启" : "仍处于开机自启状态");
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
        if (envConfigured) {
            out.append("env: ").append(envPath)
                    .append(envInSync ? " (in sync)" : " (out of sync)").append('\n');
        } else {
            out.append("env: (未配置)\n");
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
        out.append("auto-start: ").append(enabled ? "enabled" : "disabled").append('\n');
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
