package io.xac.systemd.apply;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitRenderer;

public final class ApplyContext {

    public final ServiceSpec spec;
    public final SystemdTarget target;
    public final CommandRunner runner;
    public final boolean dryRun;
    public final String unitContent;
    public final String envContent;
    /** 动作可以忽略的失败（如 unit 未安装时的 stop）；由 Runner 打印后清空。 */
    public final java.util.List<String> warnings = new java.util.ArrayList<String>();

    public ApplyContext(ServiceSpec spec, SystemdTarget target, CommandRunner runner, boolean dryRun) {
        this.spec = spec;
        this.target = target;
        this.runner = runner;
        this.dryRun = dryRun;
        UnitRenderer renderer = new UnitRenderer();
        this.unitContent = renderer.render(spec, target);
        this.envContent = renderer.renderEnvFile(spec);
    }
}
