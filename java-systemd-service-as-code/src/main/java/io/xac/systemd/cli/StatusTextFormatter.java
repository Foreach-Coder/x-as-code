package io.xac.systemd.cli;

import io.xac.systemd.status.StatusResult;

/**
 * status 面向终端的文本渲染。
 *
 * <p>输出是 CLI 契约的一部分：这里的每个字符都由 {@code StatusTextFormatterTest} 逐字节锁定。</p>
 */
public final class StatusTextFormatter {

    private StatusTextFormatter() {
    }

    public static String format(StatusResult r) {
        StringBuilder out = new StringBuilder();
        out.append("service: ").append(r.service).append('\n');
        out.append("package: ").append(r.packageDir).append('\n');
        out.append("unit: ").append(r.unitPath).append(unitState(r)).append('\n');
        if (!r.envConfigured) {
            out.append("env: (not configured)\n");
        } else if (!r.envReadable) {
            out.append("env: (cannot verify: root required)\n");
        } else {
            out.append("env: ").append(r.envPath)
                    .append(r.envInSync ? " (in sync)" : " (out of sync)").append('\n');
        }
        out.append("active: ").append(value(r, "ActiveState", "unknown"));
        String sub = r.show.get("SubState");
        if (sub != null && !sub.isEmpty()) {
            out.append(" (").append(sub).append(')');
        }
        String since = r.show.get("ActiveEnterTimestamp");
        if (since != null && !since.isEmpty()) {
            out.append(" since ").append(since);
        }
        out.append('\n');
        out.append("pid: ").append(value(r, "MainPID", "unknown")).append('\n');
        out.append("auto-start: ")
                .append(r.queryOk ? (r.enabled ? "enabled" : "disabled") : "unknown")
                .append('\n');
        for (String line : r.drift()) {
            out.append("drift: ").append(line).append('\n');
        }
        return out.toString();
    }

    private static String unitState(StatusResult r) {
        if (r.unitInSync) {
            return " (in sync)";
        }
        return r.unitInstalled ? " (out of sync)" : " (missing)";
    }

    private static String value(StatusResult r, String key, String fallback) {
        String value = r.show.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
