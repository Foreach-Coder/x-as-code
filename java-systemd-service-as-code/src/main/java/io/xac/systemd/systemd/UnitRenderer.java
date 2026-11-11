package io.xac.systemd.systemd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.ServiceSpec;

public final class UnitRenderer {

    public static final String HEADER = "# Managed by xac. Do not edit.";

    public String render(ServiceSpec spec, SystemdTarget target) {
        // 防线：即使将来新增字段忘了加校验，也不允许换行进入 unit 的物理行。
        requireSingleLine("workdir", spec.workdir == null ? null : spec.workdir.toString());
        requireSingleLine("runtime.java", spec.java);
        requireSingleLine("artifact.jar", spec.jar == null ? null : spec.jar.toString());

        List<List<String>> sections = new ArrayList<List<String>>();

        List<String> unit = new ArrayList<String>();
        unit.add("[Unit]");
        addIfPresent(unit, "Description=" + nullToEmpty(spec.description));
        sections.add(unit);

        // 顺序固定；新增指令追加在这里，不要重排既有行（设计文档 §5.2）。
        List<Directive> directives = new ArrayList<Directive>();
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "Type=simple";
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "WorkingDirectory=" + workingDirectory(s);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "SuccessExitStatus=143";
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return s.env.isEmpty()
                        ? null
                        : "EnvironmentFile=" + UnitNaming.envPath(s, target);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "SyslogIdentifier=" + UnitNaming.PREFIX + s.name;
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "ExecStart=" + execStart(s);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "Restart=" + s.lifecycle.restartOrDefault();
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "RestartSec=" + s.lifecycle.restartSecOrDefault();
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "TimeoutStopSec=" + s.lifecycle.timeoutStopSecOrDefault();
            }
        });

        List<String> service = new ArrayList<String>();
        service.add("[Service]");
        for (Directive directive : directives) {
            addIfPresent(service, directive.render(spec));
        }
        sections.add(service);

        if (spec.lifecycle.autoStartOrDefault()) {
            List<String> install = new ArrayList<String>();
            install.add("[Install]");
            install.add("WantedBy=multi-user.target");
            sections.add(install);
        }

        StringBuilder out = new StringBuilder();
        out.append(HEADER).append('\n');
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            for (String line : sections.get(i)) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    public String execStart(ServiceSpec spec) {
        List<String> argv = new ArrayList<String>();
        argv.add(spec.java);
        for (String opt : spec.opts) {
            argv.add(opt);
        }
        for (Map.Entry<String, String> e : spec.properties.entrySet()) {
            argv.add("-D" + e.getKey() + "=" + e.getValue());
        }
        argv.add("-jar");
        argv.add(spec.jar.toString());

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(escape(argv.get(i)));
        }
        return out.toString();
    }

    public String renderEnvFile(ServiceSpec spec) {
        StringBuilder out = new StringBuilder();
        // 设计文档 §5.1：两个生成文件都以管理头开始；systemd 的 EnvironmentFile 忽略 # 注释。
        out.append(HEADER).append('\n');
        for (Map.Entry<String, String> e : spec.env.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out.append(e.getKey()).append('=');
            if (!value.isEmpty()) {
                out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * 只做两件事：% → %%，以及需要引号时加双引号（引号内转义 \ 与 "）。
     * 需要引号的字符：空格、制表符、双引号、反斜杠。
     * $ 不转义：${VAR} 由 systemd 在运行时展开，$${ 是用户写的字面量转义，两者语义正好一致（D13）。
     */
    static String escape(String arg) {
        String escaped = arg.replace("%", "%%");
        boolean quote = escaped.indexOf(' ') >= 0
                || escaped.indexOf('\t') >= 0
                || escaped.indexOf('"') >= 0
                || escaped.indexOf('\\') >= 0;
        if (!quote) {
            return escaped;
        }
        return "\"" + escaped.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void requireSingleLine(String field, String value) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException(field + " must not contain a newline");
        }
    }

    private static String workingDirectory(ServiceSpec spec) {
        return spec.workdir == null ? spec.packageDir.toString() : spec.workdir.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line == null || line.isEmpty() || line.endsWith("=")) {
            return;
        }
        lines.add(line);
    }
}
