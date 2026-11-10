package io.xac.systemd.spec.validators;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class LifecycleValidator implements Validator {

    private static final Set<String> RESTART = new HashSet<String>(Arrays.asList(
            "no", "on-success", "on-failure", "on-abnormal",
            "on-abort", "on-watchdog", "always"));

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        Lifecycle lc = spec.lifecycle;
        if (lc.restart != null && !RESTART.contains(lc.restart)) {
            out.add(new Violation("lifecycle.restart",
                    "取值应为 " + RESTART + " 之一"));
        }
        if (lc.restartSec != null && lc.restartSec <= 0) {
            out.add(new Violation("lifecycle.restart-sec", "应为正整数"));
        }
        if (lc.timeoutStopSec != null && lc.timeoutStopSec <= 0) {
            out.add(new Violation("lifecycle.timeout-stop-sec", "应为正整数"));
        }
    }
}
