package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.regex.Pattern;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class NameValidator implements Validator {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.:-]+");

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        String name = spec.name;
        if (name == null) {
            return;
        }
        boolean ok = ALLOWED.matcher(name).matches()
                && !name.startsWith(".")
                && !name.contains("/");
        if (!ok) {
            out.add(new Violation("name",
                    "只能包含字母、数字和 _ . : -，不能以 . 开头，不能包含 /"));
        }
    }
}
