package io.xac.systemd.spec.validators;

import java.util.List;

import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.PathLinkGuard;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.systemd.SystemdTarget;

public final class PathLinksValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        SystemdTarget target = SystemdTarget.defaults();
        List<String> links = spec.pathLinks;
        for (int i = 0; i < links.size(); i++) {
            String raw = links.get(i);
            String path = "path-links[" + i + "]";
            PathLink link = PathLink.parse(raw, spec.packageDir);
            if (link == null) {
                out.add(new Violation(path, "应为 源:目标，且目标是以 / 开头的绝对路径"));
                continue;
            }
            String problem = PathLinkGuard.check(link, target.unitDir, target.envDir,
                    spec.packageDir);
            if (problem != null) {
                out.add(new Violation(path, problem));
            }
        }
    }
}
