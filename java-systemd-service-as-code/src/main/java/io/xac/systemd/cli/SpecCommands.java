package io.xac.systemd.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.apply.Preflight;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecLoader;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;

final class SpecCommands {

    static final class Loaded {
        final ServiceSpec spec;
        final List<Violation> violations;

        Loaded(ServiceSpec spec, List<Violation> violations) {
            this.spec = spec;
            this.violations = violations;
        }
    }

    /** 加载 + 静态校验 + 环境预检；任何一步失败都返回 spec == null。 */
    static Loaded load(Path packageDir) {
        SpecLoader.Result parsed = SpecLoader.load(packageDir);
        List<Violation> violations = new ArrayList<Violation>(parsed.violations);
        if (parsed.spec == null) {
            return new Loaded(null, violations);
        }
        violations.addAll(ValidationRunner.run(parsed.spec));
        if (violations.isEmpty()) {
            violations.addAll(Preflight.check(parsed.spec, new RealEnvironmentProbe(new ProcessRunner())));
        }
        return new Loaded(violations.isEmpty() ? parsed.spec : null, violations);
    }

    static void report(List<Violation> violations) {
        for (Violation violation : violations) {
            System.err.println(violation);
        }
    }

    private SpecCommands() {
    }
}
