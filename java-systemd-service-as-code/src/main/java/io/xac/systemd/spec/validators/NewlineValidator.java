package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

/**
 * unit 文件是一行一条指令；换行会把用户数据变成额外的物理行，等于注入指令。
 * env 的值由 EnvValidator 单独检查。
 */
public final class NewlineValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        check("description", spec.description, out);
        check("artifact.jar", spec.jar == null ? null : spec.jar.toString(), out);
        check("runtime.java", spec.java, out);
        check("workdir", spec.workdir == null ? null : spec.workdir.toString(), out);
        for (int i = 0; i < spec.opts.size(); i++) {
            check("runtime.opts[" + i + "]", spec.opts.get(i), out);
        }
        for (Map.Entry<String, String> entry : spec.properties.entrySet()) {
            check("properties", entry.getKey(), out);
            check("properties." + entry.getKey(), entry.getValue(), out);
        }
    }

    private static void check(String path, String value, List<Violation> out) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            out.add(new Violation(path, "must not contain a newline"));
        }
    }
}
