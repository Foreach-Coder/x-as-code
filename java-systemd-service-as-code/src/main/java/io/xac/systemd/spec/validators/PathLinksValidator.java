package io.xac.systemd.spec.validators;

import java.nio.file.InvalidPathException;
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
            PathLink link;
            String problem;
            try {
                link = PathLink.parse(raw, spec.packageDir);
                if (link == null) {
                    out.add(new Violation(path,
                            "must be source:target, with an absolute target starting with /"));
                    continue;
                }
                problem = PathLinkGuard.checkShape(link, target.unitDir, target.envDir,
                        spec.packageDir);
            } catch (InvalidPathException e) {
                // 用户原文可能包含当前平台不接受的路径字符；校验必须报错而不是崩溃。
                out.add(new Violation(path, "invalid target path: " + e.getMessage()));
                continue;
            }
            if (problem != null) {
                out.add(new Violation(path, problem));
            }
        }
    }
}
