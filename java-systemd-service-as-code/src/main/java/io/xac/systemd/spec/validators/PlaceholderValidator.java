package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

/**
 * 找出每个 ${NAME} 并要求 NAME 在 env 中；$${ 是字面量转义，跳过。
 * 渲染时不展开，交给 systemd 在运行时展开（D13）。
 */
public final class PlaceholderValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        for (int i = 0; i < spec.opts.size(); i++) {
            scan(spec, spec.opts.get(i), "runtime.opts[" + i + "]", out);
        }
        for (Map.Entry<String, String> e : spec.properties.entrySet()) {
            scan(spec, e.getValue(), "properties." + e.getKey(), out);
        }
    }

    private static void scan(ServiceSpec spec, String text, String path, List<Violation> out) {
        if (text == null) {
            return;
        }
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$$", i)) {
                i += 2;
                continue;
            }
            if (text.startsWith("${", i)) {
                int end = text.indexOf('}', i + 2);
                if (end < 0) {
                    out.add(new Violation(path, "placeholder is missing '}': " + text.substring(i)));
                    return;
                }
                String name = text.substring(i + 2, end);
                if (name.isEmpty() || !spec.env.containsKey(name)) {
                    out.add(new Violation(path, "placeholder ${" + name + "} is not defined in env"));
                }
                i = end + 1;
                continue;
            }
            i++;
        }
    }
}
