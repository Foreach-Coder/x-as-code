package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class EnvValidator implements Validator {

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        for (Map.Entry<String, String> entry : spec.env.entrySet()) {
            String path = "env." + entry.getKey();
            if (!KEY.matcher(entry.getKey()).matches()) {
                out.add(new Violation(path, "键名只能包含字母、数字和下划线，且不能以数字开头"));
            }
            String value = entry.getValue();
            if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                out.add(new Violation(path, "值不能包含换行"));
            }
        }
    }
}
