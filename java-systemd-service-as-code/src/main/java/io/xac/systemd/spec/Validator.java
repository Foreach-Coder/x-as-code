package io.xac.systemd.spec;

import java.util.List;

public interface Validator {

    void validate(ServiceSpec spec, List<Violation> out);
}
