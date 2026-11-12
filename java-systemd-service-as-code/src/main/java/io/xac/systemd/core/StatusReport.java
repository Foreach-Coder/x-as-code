package io.xac.systemd.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.status.StatusResult;

/** status 的结构化结果；CLI 负责把 status 与 drift 渲染成人读文本。 */
public final class StatusReport {

    public final StatusResult status;
    public final List<String> drift;
    public final boolean healthy;

    public StatusReport(StatusResult status, List<String> drift, boolean healthy) {
        this.status = status;
        this.drift = Collections.unmodifiableList(new ArrayList<String>(drift));
        this.healthy = healthy;
    }
}
