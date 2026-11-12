package io.xac.systemd.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.ApplyOutcome;
import io.xac.systemd.apply.ApplyRunner;
import io.xac.systemd.apply.DestroyOutcome;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.apply.EnvironmentProbe;
import io.xac.systemd.apply.Preflight;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.apply.StepPlan;
import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.FileLogStream;
import io.xac.systemd.logs.JournaldLogStream;
import io.xac.systemd.logs.LogStream;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecLoader;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.status.StatusRunner;
import io.xac.systemd.systemd.SystemdTarget;

/**
 * 一个 xac 包目录的门面：加载 + 静态校验 + 预检 + 执行。
 *
 * <p>只返回结构化结果，不拼任何面向人的英文句子；输出由 CLI 负责。
 * {@link #open(Path)} 不接触 systemd、不要求 root、不写文件。
 */
public final class ServicePackage {

    private final ServiceSpec spec;
    private final Path packageDir;
    private final CommandRunner runner;
    private final EnvironmentProbe probe;
    private final SystemdTarget target;

    private ServicePackage(ServiceSpec spec, Path packageDir, CommandRunner runner,
            EnvironmentProbe probe, SystemdTarget target) {
        this.spec = spec;
        this.packageDir = packageDir;
        this.runner = runner;
        this.probe = probe;
        this.target = target;
    }

    public static ServicePackage open(Path packageDir) throws InvalidSpecException {
        Path dir = packageDir.toAbsolutePath().normalize();
        ProcessRunner runner = new ProcessRunner();
        return load(dir, runner, new RealEnvironmentProbe(runner), SystemdTarget.defaults());
    }

    /** 注入依赖的入口：CLI 与测试用它替换 systemd / root / 命令执行。 */
    public static ServicePackage open(Path packageDir, CommandRunner runner,
            EnvironmentProbe probe, SystemdTarget target) throws InvalidSpecException {
        return load(packageDir.toAbsolutePath().normalize(), runner, probe, target);
    }

    private static ServicePackage load(Path dir, CommandRunner runner,
            EnvironmentProbe probe, SystemdTarget target) throws InvalidSpecException {
        SpecLoader.Result loaded = SpecLoader.load(dir);
        if (loaded.spec == null) {
            throw new InvalidSpecException(loaded.violations);
        }
        List<Violation> violations = ValidationRunner.run(loaded.spec);
        if (!violations.isEmpty()) {
            throw new InvalidSpecException(violations);
        }
        return new ServicePackage(loaded.spec, dir, runner, probe, target);
    }

    public ServiceSpec spec() {
        return spec;
    }

    public Path packageDir() {
        return packageDir;
    }

    public boolean isRoot() {
        return probe.isRoot();
    }

    /** 只读：不要求 root，不执行任何动作。 */
    public StatusReport status() {
        StatusResult result = new StatusRunner().run(spec, target, runner);
        return new StatusReport(result, result.drift(), result.healthy());
    }

    /** 只读：不预检、不执行任何动作。 */
    public List<StepPlan> plan() {
        ApplyContext ctx = new ApplyContext(spec, target, runner, true);
        List<StepPlan> plans = new ArrayList<StepPlan>();
        for (Action action : ApplyRunner.actions()) {
            plans.add(new StepPlan(action.describe(ctx), false));
        }
        return Collections.unmodifiableList(plans);
    }

    /** 非 follow 的日志来源：默认 journald，配置了 {@code logs.file} 时读文件。 */
    public LogStream logs(int lines) {
        return logs(lines, false);
    }

    /**
     * 只读：不预检、不要求 root、不执行任何动作，只返回可消费的日志来源。
     *
     * @throws LogSourceException 行数非正，或日志文件不存在/不可读
     */
    public LogStream logs(int lines, boolean follow) {
        if (lines <= 0) {
            throw new LogSourceException("-n must be a positive integer");
        }
        if (spec.logFile != null) {
            return new FileLogStream(Paths.get(spec.logFile), 200L, follow);
        }
        return new JournaldLogStream(runner, spec, follow);
    }

    public ApplyOutcome apply(boolean dryRun) throws InvalidSpecException {
        requirePreflight(Preflight.check(spec, probe, runner, target));
        return new ApplyRunner().run(new ApplyContext(spec, target, runner, dryRun));
    }

    /** destroy 的只读计划：预检后只读文件系统，零动作，供确认面板使用。 */
    public DestroyOutcome destroyPlan() throws InvalidSpecException {
        return destroyPlan(new DestroyRunner());
    }

    /** 测试/入口层注入用，非稳定 API。 */
    public DestroyOutcome destroyPlan(DestroyRunner destroyRunner) throws InvalidSpecException {
        requirePreflight(Preflight.checkDestroy(probe));
        return destroyRunner.plan(spec, target);
    }

    public DestroyOutcome destroy(boolean dryRun) throws InvalidSpecException {
        return destroy(dryRun, new DestroyRunner());
    }

    /** 唯一编排入口：预检 + `DestroyRunner.run`。测试/入口层注入用，非稳定 API。 */
    public DestroyOutcome destroy(boolean dryRun, DestroyRunner destroyRunner)
            throws InvalidSpecException {
        requirePreflight(Preflight.checkDestroy(probe));
        return destroyRunner.run(new ApplyContext(spec, target, runner, dryRun));
    }

    private static void requirePreflight(List<Violation> violations)
            throws InvalidSpecException {
        if (!violations.isEmpty()) {
            throw new InvalidSpecException(violations);
        }
    }
}
