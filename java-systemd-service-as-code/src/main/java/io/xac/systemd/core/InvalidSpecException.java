package io.xac.systemd.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.spec.Violation;

/** 规格无效：加载失败、静态校验失败，或 apply / destroy 的预检失败。 */
public final class InvalidSpecException extends Exception {

    public final List<Violation> violations;

    public InvalidSpecException(List<Violation> violations) {
        super("invalid spec (" + violations.size() + " violation(s))");
        this.violations = Collections.unmodifiableList(
                new ArrayList<Violation>(violations));
    }
}
