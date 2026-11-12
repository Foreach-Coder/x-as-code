package io.xac.systemd.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.DisableAction;
import io.xac.systemd.apply.actions.ManagedUnit;
import io.xac.systemd.apply.actions.RemoveEnvAction;
import io.xac.systemd.apply.actions.RemoveLinksAction;
import io.xac.systemd.apply.actions.RemoveUnitAction;
import io.xac.systemd.apply.actions.StopAction;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;

public final class DestroyRunner {

    /** 顺序（设计文档 §6.5）：先停服务，再删文件，最后移除链接。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new StopAction(),
                new DisableAction(),
                new RemoveUnitAction(),
                new RemoveEnvAction(),
                // destroy 的语义是「让这些东西不存在」；reload 失败只警告（容器/无 systemd 场景）。
                new DaemonReloadAction(true),
                new RemoveLinksAction());
    }

    /**
     * 只读地算出 destroy 会删什么、会保留什么。不改文件、不调用 systemctl；
     * 与 {@link #run(ApplyContext)} 使用同一套归属判断。
     */
    public DestroyOutcome plan(ServiceSpec spec, SystemdTarget target) {
        Inspection inspection = inspect(spec, target);
        return new DestroyOutcome(
                willRemove(spec, inspection),
                willKeep(spec),
                Collections.<StepResult>emptyList(),
                Collections.<String>emptyList(),
                null);
    }

    /**
     * 执行 destroy。归属判断先于任何 stop/disable；dry-run 也走同一判断，
     * 只返回步骤而不执行。
     */
    public DestroyOutcome run(ApplyContext ctx) {
        Inspection inspection = inspect(ctx.spec, ctx.target);
        List<String> willRemove = willRemove(ctx.spec, inspection);
        List<String> willKeep = willKeep(ctx.spec);
        List<StepResult> steps = new ArrayList<StepResult>();
        List<String> warnings = new ArrayList<String>();

        boolean skipUnitActions = !inspection.managed;
        if (skipUnitActions) {
            // 无法证明归属就不 stop/disable，但必须让用户知道可能还有同名进程在跑。
            ctx.warnings.add("unit with same name is not managed by xac, not stopped; "
                    + "stop it manually if a process is still running: " + inspection.unitPath);
        }
        if (inspection.pathExists && !inspection.regularFile) {
            warnings.add(addWarningStep(steps,
                    "unit path is not a regular file, skipped: " + inspection.unitPath));
        } else if (inspection.regularFile && !inspection.managed) {
            // 归属判断必须发生在 stop / disable 之前，否则会先动别人的服务再只发一条警告。
            warnings.add(addWarningStep(steps,
                    "unit is not managed by xac, skipped stop/disable/remove: " + inspection.unitPath));
        }

        for (Action action : actions()) {
            if (skipUnitActions && isUnitAction(action)) {
                continue;
            }
            StepPlan stepPlan = new StepPlan(action.describe(ctx),
                    !(action instanceof DaemonReloadAction));
            if (ctx.dryRun) {
                steps.add(new StepResult(stepPlan, true, null));
                continue;
            }
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                StepResult failed = new StepResult(stepPlan, false, e.getMessage());
                steps.add(failed);
                warnings.addAll(ctx.warnings);
                ctx.warnings.clear();
                return new DestroyOutcome(willRemove, willKeep, steps, warnings, failed);
            }
            collectWarnings(ctx, stepPlan, steps, warnings);
        }
        return new DestroyOutcome(willRemove, willKeep, steps, warnings, null);
    }

    private static Inspection inspect(ServiceSpec spec, SystemdTarget target) {
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);
        // 只有「unitPath 上确实存在由 xac 管理的普通文件」才证明归属；否则 systemd 可能
        // 从 /usr/lib/systemd/system 加载了同名 unit（如 ssh），绝不能 stop/disable 它。
        boolean pathExists = Files.exists(unitPath, LinkOption.NOFOLLOW_LINKS);
        boolean regularFile = Files.isRegularFile(unitPath, LinkOption.NOFOLLOW_LINKS);
        boolean managed = false;
        if (regularFile) {
            try {
                managed = ManagedUnit.hasHeader(unitPath);
            } catch (IOException e) {
                throw new ActionFailure("failed to read unit file " + unitPath + ": " + e.getMessage(), e);
            }
        }
        return new Inspection(unitPath, envPath, pathExists, regularFile, managed);
    }

    private static List<String> willRemove(ServiceSpec spec, Inspection inspection) {
        List<String> paths = new ArrayList<String>();
        if (inspection.managed) {
            paths.add(inspection.unitPath.toString());
        }
        if (Files.exists(inspection.envPath)) {
            paths.add(inspection.envPath.toString());
        }
        for (String raw : spec.pathLinks) {
            PathLink link = PathLink.parse(raw, spec.packageDir);
            if (link == null) {
                continue;
            }
            try {
                if (Files.isSymbolicLink(link.target)
                        && Files.readSymbolicLink(link.target).equals(link.source)) {
                    paths.add(link.target.toAbsolutePath().normalize().toString());
                }
            } catch (IOException e) {
                throw new ActionFailure("failed to read symlink " + link.target
                        + ": " + e.getMessage(), e);
            }
        }
        return paths;
    }

    private static List<String> willKeep(ServiceSpec spec) {
        Path workdir = spec.workdir == null ? spec.packageDir : spec.workdir;
        return Arrays.asList(
                "package directory " + spec.packageDir,
                "workdir " + workdir,
                "logs");
    }

    /** 把 action 累积的 warning 变成步骤；第一条挂在步骤本身上，其余各自成 warning-only 步骤。 */
    private static void collectWarnings(ApplyContext ctx, StepPlan stepPlan,
                                        List<StepResult> steps, List<String> warnings) {
        if (ctx.warnings.isEmpty()) {
            steps.add(new StepResult(stepPlan, true, null));
            return;
        }
        String first = ctx.warnings.get(0);
        steps.add(new StepResult(stepPlan, true, first));
        warnings.add(first);
        for (int i = 1; i < ctx.warnings.size(); i++) {
            String warning = ctx.warnings.get(i);
            steps.add(new StepResult(null, true, warning));
            warnings.add(warning);
        }
        ctx.warnings.clear();
    }

    private static String addWarningStep(List<StepResult> steps, String message) {
        steps.add(new StepResult(null, true, message));
        return message;
    }

    private static boolean isUnitAction(Action action) {
        return action instanceof StopAction
                || action instanceof DisableAction
                || action instanceof RemoveUnitAction;
    }

    private static final class Inspection {
        final Path unitPath;
        final Path envPath;
        final boolean pathExists;
        final boolean regularFile;
        final boolean managed;

        Inspection(Path unitPath, Path envPath, boolean pathExists,
                   boolean regularFile, boolean managed) {
            this.unitPath = unitPath;
            this.envPath = envPath;
            this.pathExists = pathExists;
            this.regularFile = regularFile;
            this.managed = managed;
        }
    }
}
