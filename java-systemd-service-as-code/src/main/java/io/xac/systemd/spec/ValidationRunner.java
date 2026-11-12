package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.spec.validators.EnvValidator;
import io.xac.systemd.spec.validators.LifecycleValidator;
import io.xac.systemd.spec.validators.LogsValidator;
import io.xac.systemd.spec.validators.NameValidator;
import io.xac.systemd.spec.validators.NewlineValidator;
import io.xac.systemd.spec.validators.PathLinksValidator;
import io.xac.systemd.spec.validators.PlaceholderValidator;

public final class ValidationRunner {

    /** 顺序固定：输出稳定，测试可断言下标。 */
    private static final List<Validator> VALIDATORS = Arrays.<Validator>asList(
            new NameValidator(),
            new EnvValidator(),
            new NewlineValidator(),
            new PathLinksValidator(),
            new PlaceholderValidator(),
            new LifecycleValidator(),
            new LogsValidator());

    public static List<Violation> run(ServiceSpec spec) {
        List<Violation> out = new ArrayList<Violation>();
        for (Validator validator : VALIDATORS) {
            validator.validate(spec, out);
        }
        return out;
    }

    private ValidationRunner() {
    }
}
