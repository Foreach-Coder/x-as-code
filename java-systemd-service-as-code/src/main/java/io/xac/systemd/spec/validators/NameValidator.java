package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class NameValidator implements Validator {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.:-]+");

    private static final String CHARSET_MESSAGE =
            "must contain only letters, digits and _ . : -, must not start with '.', must not contain '/'";

    private static final String PREFIX_MESSAGE =
            "must not start with 'xac.'; the tool adds that prefix automatically";

    /** 返回 null 表示合法；供 init 在提问时复用同一套规则。 */
    public static Violation violationFor(String name) {
        if (name == null) {
            return null;
        }
        boolean allowed = ALLOWED.matcher(name).matches()
                && !name.startsWith(".")
                && !name.contains("/");
        if (!allowed) {
            return new Violation("name", CHARSET_MESSAGE);
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("xac.")) {
            return new Violation("name", PREFIX_MESSAGE);
        }
        return null;
    }

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        Violation violation = violationFor(spec.name);
        if (violation != null) {
            out.add(violation);
        }
    }
}
