package io.xac.systemd.spec.validators;

import java.util.List;

import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class PathLinksValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        List<String> links = spec.pathLinks;
        for (int i = 0; i < links.size(); i++) {
            String raw = links.get(i);
            if (PathLink.parse(raw, spec.packageDir) == null) {
                out.add(new Violation("path-links[" + i + "]",
                        "应为 源:目标，且目标是以 / 开头的绝对路径"));
            }
        }
    }
}
