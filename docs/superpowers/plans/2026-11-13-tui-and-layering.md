# TUI 与三层分层 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `java-systemd-service-cli` 增加一个基于 Lanterna 的 TUI 入口，并把现有 CLI 的业务编排抽到 `core` 层；两个入口共用同一套能力，TUI 支持状态/日志查看、apply/restart/destroy 与 `xac.yaml` 的结构化编辑。

**Architecture:** 单 Maven 模块，新增 `core` 与 `tui` 两个包。依赖方向固定为 `cli → core`、`tui → core`、`core → spec/systemd/exec/apply/status/logs`；`cli` 与 `tui` 互不依赖；`spec/systemd/exec` 不反向依赖。TUI 的渲染与键位都通过 `Canvas`/`Display`/`KeySource` 抽象，测试不启动真实终端。

**Tech Stack:** Java 21 编译（`maven.compiler.release=8`）、Maven、picocli 4.7.6、SnakeYAML 2.2、Lanterna 3.1.5（唯一新增依赖）、JUnit 5.10.2、maven-shade-plugin。

**Spec:** `docs/superpowers/specs/2026-11-13-tui-and-layering-design.md`（下称「设计文档」）。

## Global Constraints

- 编译：`maven.compiler.release=8`。禁止 `record`、`var`、switch 表达式、文本块、`List.of`/`Map.of`、`Optional.isEmpty()`、`ProcessHandle`、`Stream.toList()`。
- 依赖白名单：picocli 4.7.6、SnakeYAML 2.2、JUnit 5.10.2、Lanterna 3.1.5（Task 7 才加）。不新增其它依赖。
- 外部命令一律 `ProcessBuilder` + `List<String>` argv；禁止 shell 字符串拼接。
- **CLI 行为契约**：Task 1–6 完成后，`apply`/`status`/`logs`/`destroy` 的 stdout/stderr 与退出码逐字节不变（现有 180 单测 + 10 e2e 是契约）。任何不得不改的文案，必须在提交信息里写明原因。
- 渲染确定性：`SpecWriter` 输出只依赖模型与注释表，键序固定，LF，结尾单个 `\n`。
- 面向用户输出：标签英文、提示与错误中文（沿用现状）；内部异常消息英文。
- 每个 Task 结束必须 `mvn -q -f java-systemd-service-as-code/pom.xml test` 全绿并提交。
- 命令都在仓库根 `D:\Develop\xac` 执行；下文简写 `MVN = mvn -q -f java-systemd-service-as-code/pom.xml`。

## 文件结构

```
java-systemd-service-as-code/src/main/java/io/xac/systemd/
  exec/RunningCommand.java            新增：可流式读取的子进程句柄
  exec/CommandRunner.java             改：新增 start(argv)
  exec/ProcessRunner.java             改：实现 start
  exec/RecordingRunner.java           改：start 记录并可预置输出
  status/StatusResult.java            改：只留数据与 drift()
  cli/StatusTextFormatter.java        新增：status 文本（从 StatusResult 移出）
  apply/StepPlan.java                 新增：一步的描述 + 是否破坏性
  apply/StepResult.java               新增：一步的结果
  apply/ApplyOutcome.java             新增：apply 的返回值
  apply/DestroyOutcome.java           新增：destroy 的返回值
  apply/ApplyRunner.java              改：返回 ApplyOutcome，不打印
  apply/DestroyRunner.java            改：返回 DestroyOutcome，不打印
  core/ServicePackage.java            新增：唯一门面
  core/InvalidSpecException.java      新增：带 violations 的受检异常
  core/StatusReport.java              新增：StatusResult + drift + healthy
  core/LogSourceException.java        新增：日志来源不可用
  logs/LogSink.java                   新增：一行回调
  logs/LogStream.java                 新增：start/close
  logs/FileLogStream.java             新增：FileTail 的流实现
  logs/JournaldLogStream.java         新增：journalctl --follow 的流实现
  logs/LogsRunner.java                删除（能力搬进 core + LogStream）
  spec/SpecWriter.java                新增：模型 → 规范 YAML 文本
  spec/SpecSaver.java                 新增：原子写文件
  spec/YamlCommentScanner.java        新增：注释扫描与挂回路径
  spec/Comments.java                  新增：line → inline、path → leading 的表
  spec/LineDiff.java                  新增：逐行 diff 文本
  tui/TuiCommand.java                 新增：picocli 子命令
  tui/TuiApp.java                     新增：主循环与状态机
  tui/Canvas.java                     新增：字符画布
  tui/Display.java                    新增：画布输出抽象
  tui/LanternaDisplay.java            新增：Screen 适配
  tui/KeySource.java                  新增：按键来源
  tui/KeyEvent.java                   新增：抽象按键
  tui/LanternaKeySource.java          新增：KeyStroke → KeyEvent
  tui/NavKey.java                     新增：按键枚举
  tui/TuiState.java                   新增：可渲染状态
  tui/StatusView.java                 新增：StatusReport → 状态行
  tui/actions/ActionOverlay.java      新增：apply/destroy/restart 覆盖层
  tui/edit/EditorModel.java           新增：字段树与草稿
  tui/edit/EditorField.java           新增：一个字段
  tui/edit/EditScreen.java            新增：表单交互状态机
  tui/edit/EditorView.java            新增：表单渲染
  tui/edit/SaveFlow.java              新增：校验 → diff → 原子写
```

---

### Task 1: exec 层新增 RunningCommand

**Files:**
- Create: `exec/RunningCommand.java`
- Modify: `exec/CommandRunner.java`、`exec/ProcessRunner.java`、`exec/RecordingRunner.java`
- Create: `src/test/java/io/xac/systemd/exec/ExecFixtures.java`、`exec/PrintThenWait.java`、`exec/RunningCommandTest.java`
- Modify: `src/test/java/io/xac/systemd/exec/ProcessRunnerTest.java`（改用 `ExecFixtures`）

**Interfaces:**
```java
public interface RunningCommand {
    java.io.InputStream stdout();
    java.io.InputStream stderr();
    int waitFor() throws InterruptedException;
    void destroy();
    boolean isAlive();
}

public interface CommandRunner {
    ExecResult run(List<String> argv);
    void runInheritIo(List<String> argv);
    RunningCommand start(List<String> argv);   // 新增
}

// RecordingRunner 新增
public void addStartedOutput(List<String> argv, String stdout, String stderr, boolean blockUntilDestroyed);
public List<List<String>> startedCalls();

// test 工具
public final class ExecFixtures {
    public static String classpath();
    public static List<String> java(String mainClass, String... args);
    public static final class LinePrinter { public static void main(String[] a); } // 打印 A/B/C 后 exit 0
    public static final class Waiter { public static void main(String[] a); }       // 打印 READY 后 sleep 60s
}
```

- [ ] **Step 1: 写失败测试**

`RunningCommandTest`：
```java
@Test void streamsStdoutAndWaitsForExit() throws Exception {
    RunningCommand cmd = new ProcessRunner().start(ExecFixtures.java("io.xac.systemd.exec.LinePrinter"));
    List<String> lines = readLines(cmd.stdout(), 3);
    assertEquals(Arrays.asList("A", "B", "C"), lines);
    assertEquals(0, cmd.waitFor());
    assertFalse(cmd.isAlive());
}

@Test void destroyTerminatesProcess() throws Exception {
    RunningCommand cmd = new ProcessRunner().start(ExecFixtures.java("io.xac.systemd.exec.Waiter"));
    cmd.stdout().read();                       // 等 READY 首字节，确保已起
    assertTrue(cmd.isAlive());
    cmd.destroy();
    cmd.waitFor();
    assertFalse(cmd.isAlive());
}

@Test void recordingRunnerScriptsStartedCommands() throws Exception { /* addStartedOutput + 读 3 行 + destroy */ }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `MVN test -Dtest=RunningCommandTest`
Expected: COMPILATION ERROR —— `CommandRunner` 没有 `start`，`ExecFixtures` 不存在。

- [ ] **Step 3: 最小实现**

`ProcessRunner.start`：
```java
@Override
public RunningCommand start(List<String> argv) {
    final Process process;
    try {
        process = new ProcessBuilder(argv).start();
    } catch (IOException e) {
        throw new IllegalStateException("cannot execute " + argv.get(0) + ": " + e.getMessage(), e);
    }
    return new RunningCommand() {
        public InputStream stdout() { return process.getInputStream(); }
        public InputStream stderr() { return process.getErrorStream(); }
        public int waitFor() throws InterruptedException { return process.waitFor(); }
        public void destroy() { process.destroy(); }
        public boolean isAlive() { return process.isAlive(); }
    };
}
```

`RecordingRunner.start`：记录 argv 到 `startedCalls()`；返回一个 `RunningCommand`，其 `stdout()` 输出预置文本后，若 `blockUntilDestroyed` 为真则阻塞在 `CountDownLatch.await()`，`destroy()` 计数归零并置 `destroyed`；`waitFor()` 返回预置退出码（缺省 0）；`isAlive()` 返回 `!destroyed && (blockUntilDestroyed || !eofRead)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test -Dtest=RunningCommandTest`
Expected: `Tests run: 3, Failures: 0`。再跑 `MVN test`，Expected: 180 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: exec 层新增 RunningCommand 支持流式读取子进程"
```

---

### Task 2: status 文本从 StatusResult 移出

**Files:**
- Create: `cli/StatusTextFormatter.java`
- Modify: `status/StatusResult.java`、`cli/StatusCommand.java`
- Create: `src/test/java/io/xac/systemd/cli/StatusTextFormatterTest.java`
- Modify: `src/test/java/io/xac/systemd/status/StatusResultTest.java`（删掉 format 相关断言）

**Interfaces:**
```java
public final class StatusTextFormatter {
    public static String format(StatusResult result);
}
```

- [ ] **Step 1: 写失败测试**

把 `StatusResultTest` 里所有 `result.format()` 的用例整体搬到 `StatusTextFormatterTest`（构造同样的 StatusResult），断言字符串完全不变。再补一条：

```java
@Test void keepsMissingAndUnreadableEnvDistinct() { /* unitInstalled=false → "(missing)"；envReadable=false → "(cannot verify: root required)" */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=StatusTextFormatterTest`
Expected: COMPILATION ERROR —— 没有 `StatusTextFormatter`。

- [ ] **Step 3: 最小实现**

把 `StatusResult.format()`、`unitState()`、`value()` 的方法体原样复制进 `StatusTextFormatter.format(StatusResult r)`（把 `this.` 改成 `r.`，`show` 改成 `r.show`）。从 `StatusResult` 删除这三个方法。`StatusCommand` 改为 `System.out.print(StatusTextFormatter.format(result));`。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 181 tests, 0 failures（新增 1 条）。`git diff` 里 `StatusResult.java` 只删不增。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "refactor: status 文本从 StatusResult 移到 cli 层"
```

---

### Task 3: ApplyRunner 返回结果，不再打印

**Files:**
- Create: `apply/StepPlan.java`、`apply/StepResult.java`、`apply/ApplyOutcome.java`
- Modify: `apply/ApplyRunner.java`、`cli/ApplyCommand.java`
- Modify: `src/test/java/io/xac/systemd/apply/ApplyRunnerTest.java`、`src/test/java/io/xac/systemd/cli/CliTest.java`

**Interfaces:**
```java
public final class StepPlan {
    public final String description;
    public final boolean destructive;
    public StepPlan(String description, boolean destructive);
}
public final class StepResult {
    public final StepPlan plan;
    public final boolean ok;
    public final String message;   // 失败时非 null
}
public final class ApplyOutcome {
    public final List<StepResult> steps;
    public final boolean performed;   // false = dry-run
    public final StepResult failed;   // 首个失败步，无失败为 null
}
public ApplyOutcome run(ApplyContext ctx);   // 不再接受 PrintStream
```

- [ ] **Step 1: 写失败测试**

`ApplyRunnerTest` 改断言风格：
```java
@Test void dryRunDescribesSevenStepsWithoutTouchingFiles() {
    ApplyOutcome outcome = new ApplyRunner().run(dryRunCtx);
    assertEquals(7, outcome.steps.size());
    assertFalse(outcome.performed);
    assertNull(outcome.failed);
    assertEquals("link …", outcome.steps.get(0).plan.description);
}

@Test void stopsAtFirstFailure() { /* RestartAction 抛 ActionFailure → steps 以失败步结尾，failed.message 含原因 */ }
```

`ApplyCommandTest` 补三条全文断言（dry-run 7 行、正常 7 行、失败 6 行），确保命令层输出不变。

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=ApplyRunnerTest`
Expected: COMPILATION ERROR —— `run(ApplyContext, PrintStream)` 与返回 `int`。

- [ ] **Step 3: 最小实现**

```java
public ApplyOutcome run(ApplyContext ctx) {
    List<StepResult> steps = new ArrayList<StepResult>();
    for (Action action : actions()) {
        StepPlan plan = new StepPlan(action.describe(ctx), false);
        if (ctx.dryRun) { steps.add(new StepResult(plan, true, null)); continue; }
        try {
            action.run(ctx);
            steps.add(new StepResult(plan, true, null));
        } catch (ActionFailure e) {
            StepResult failed = new StepResult(plan, false, e.getMessage());
            steps.add(failed);
            return new ApplyOutcome(steps, true, failed);
        }
    }
    return new ApplyOutcome(steps, !ctx.dryRun, null);
}
```

`ApplyCommand` 打印：
```java
ApplyOutcome outcome = new ApplyRunner().run(ctx);
for (StepResult step : outcome.steps) {
    out.println(outcome.performed ? step.plan.description : "would " + step.plan.description);
}
if (outcome.failed != null) { out.println("failed: " + outcome.failed.message); return 1; }
return 0;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 182 tests, 0 failures。`apply --dry-run` 手工输出与改前逐行相同（可用 `git stash` 对比）。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "refactor: ApplyRunner 返回结构化的 ApplyOutcome"
```

---

### Task 4: DestroyRunner 返回结果，不再打印

**Files:**
- Create: `apply/DestroyOutcome.java`
- Modify: `apply/DestroyRunner.java`、`cli/DestroyCommand.java`
- Modify: `src/test/java/io/xac/systemd/apply/DestroyRunnerTest.java`、`cli/CliTest.java`

**Interfaces:**
```java
public final class DestroyOutcome {
    public final List<String> willRemove;
    public final List<String> willKeep;
    public final List<StepResult> steps;
    public final List<String> warnings;
    public final StepResult failed;
}
public DestroyOutcome plan(ServiceSpec spec, SystemdTarget target);   // 纯计算，读文件系统但不改
public DestroyOutcome run(ApplyContext ctx);
```

- [ ] **Step 1: 写失败测试**

```java
@Test void planListsManagedUnitEnvAndMatchingLinksOnly() { /* 真目录、陌生软链不列入 */ }
@Test void foreignUnitSkipsStopDisableAndWarns() { /* 记录不到 systemctl stop；warnings 含 not managed by xac */ }
@Test void dryRunPerformsNothingAndReportsSteps() { }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=DestroyRunnerTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

把 `DestroyCommand.printPlan` 的组装逻辑搬进 `DestroyRunner.plan`，返回 `willRemove`（unit 路径、env 路径、匹配链接）、`willKeep`（`package directory …`、`workdir …`、`logs`）。`run` 保持现有归属判断与顺序，把每步替换为 `StepResult`；`ctx.warnings` 的文本进入 `outcome.warnings`；`ActionFailure` → `failed`。`DestroyCommand` 只负责打印：

```java
DestroyOutcome plan = runner.plan(spec, target);
out.println("will remove:");
for (String p : plan.willRemove) out.println("  " + p);
if (plan.willRemove.isEmpty()) out.println("  (none)");
out.println("keep: " + String.join(", ", plan.willKeep));   // Java 8 有 String.join
// 确认后
DestroyOutcome outcome = runner.run(ctx);
for (StepResult step : outcome.steps) {
    if (step.plan == null) continue;   // warning-only 步骤：只有 message，没有动作描述
    out.println(ctx.dryRun ? "would " + step.plan.description : step.plan.description);
}
for (String w : outcome.warnings) out.println("warning: " + w);
return outcome.failed == null ? 0 : 1;
```

注意：原实现把 warning 打在**该步描述之后**、下一步之前。新实现用 `StepResult.message` 承载：`ok=false` 时是失败原因；`ok=true` 且 `message != null` 时是“完成但有话要说”，CLI 在该行后打印 `warning: <message>`。`DestroyOutcome.warnings` 只保留汇总视图（供 TUI 面板与最终摘要），不再单独驱动 CLI 打印。warning-only 步骤的 `plan` 为 `null`（destroy 用它保留循环前 warning 的位置），消费方必须判空。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 185 tests, 0 failures；`destroy --dry-run --yes` 输出与改前逐行相同。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "refactor: DestroyRunner 返回结构化的 DestroyOutcome"
```

---

### Task 5: core 门面 ServicePackage，CLI 迁过去

**Files:**
- Create: `core/ServicePackage.java`、`core/InvalidSpecException.java`、`core/StatusReport.java`
- Modify: `cli/ApplyCommand.java`、`cli/StatusCommand.java`、`cli/DestroyCommand.java`、`cli/LogsCommand.java`（改走 `ServicePackage.open`，仍调用旧 `LogsRunner`）、`cli/SpecCommands.java`（删除）
- Create: `src/test/java/io/xac/systemd/core/ServicePackageTest.java`
- Modify: `cli/CliTest.java`（断言不变，若内部夹具引用 `SpecCommands` 则改为 facade）

**Interfaces:**
```java
public final class ServicePackage {
    public static ServicePackage open(Path packageDir) throws InvalidSpecException;
    static ServicePackage open(Path packageDir, CommandRunner runner,
                               EnvironmentProbe probe, SystemdTarget target) throws InvalidSpecException;
    public ServiceSpec spec();
    public Path packageDir();
    public boolean isRoot();
    public StatusReport status();
    public List<StepPlan> plan();
    public ApplyOutcome apply(boolean dryRun) throws InvalidSpecException;
    public DestroyOutcome destroy(boolean dryRun) throws InvalidSpecException;
}

public final class InvalidSpecException extends Exception {
    public final List<Violation> violations;
    public InvalidSpecException(List<Violation> violations);
}

public final class StatusReport {
    public final StatusResult status;
    public final List<String> drift;
    public final boolean healthy;
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void openRejectsInvalidYamlWithoutTouchingSystemd() { /* 临时目录写坏 yaml → assertThrows，violations 非空，runner.startedCalls/calls 为空 */ }
@Test void planDescribesSevenStepsAndRunsNothing() { /* calls 为空 */ }
@Test void applyDryRunRunsNoCommandsAndWritesNoFiles() { /* calls 为空；/etc 不可写也没关系 */ }
@Test void applyNonDryRunRequiresRoot() { /* probe.isRoot=false → InvalidSpecException 含 "must run as root" */ }
@Test void statusDoesNotRequireRoot() { /* probe.isRoot=false 仍可调用 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=ServicePackageTest`
Expected: COMPILATION ERROR —— `io.xac.systemd.core` 不存在。

- [ ] **Step 3: 最小实现**

```java
public static ServicePackage open(Path packageDir) throws InvalidSpecException {
    ProcessRunner runner = new ProcessRunner();
    return open(packageDir, runner, new RealEnvironmentProbe(runner), SystemdTarget.defaults());
}

static ServicePackage open(Path dir, CommandRunner runner, EnvironmentProbe probe,
        SystemdTarget target) throws InvalidSpecException {
    SpecLoader.Result parsed = SpecLoader.load(dir);
    List<Violation> violations = new ArrayList<Violation>(parsed.violations);
    if (parsed.spec != null) violations.addAll(ValidationRunner.run(parsed.spec));
    if (parsed.spec == null || !violations.isEmpty()) throw new InvalidSpecException(violations);
    return new ServicePackage(parsed.spec, runner, probe, target);
}

public boolean isRoot() { return probe.isRoot(); }
public StatusReport status() {
    StatusResult r = new StatusRunner().run(spec, target, runner);
    return new StatusReport(r, r.drift(), r.healthy());
}
public List<StepPlan> plan() {
    ApplyContext ctx = new ApplyContext(spec, target, runner, true);
    List<StepPlan> out = new ArrayList<StepPlan>();
    for (Action a : ApplyRunner.actions()) out.add(new StepPlan(a.describe(ctx), false));
    return out;
}
public ApplyOutcome apply(boolean dryRun) throws InvalidSpecException {
    requirePreflight(Preflight.check(spec, probe, runner, target));
    return new ApplyRunner().run(new ApplyContext(spec, target, runner, dryRun));
}
public DestroyOutcome destroy(boolean dryRun) throws InvalidSpecException {
    requirePreflight(Preflight.checkDestroy(probe));
    DestroyRunner r = new DestroyRunner();
    ApplyContext ctx = new ApplyContext(spec, target, runner, dryRun);
    DestroyOutcome plan = r.plan(spec, target);
    DestroyOutcome done = r.run(ctx);
    return new DestroyOutcome(plan.willRemove, plan.willKeep, done.steps, done.warnings, done.failed);
}
```

CLI 四个命令改为：`ServicePackage pkg = ServicePackage.open(dir);`，捕获 `InvalidSpecException` → 逐条 `System.err.println(v)` → 返回 2。`LogsCommand` 只把加载换成 `open`，日志读取仍用旧 `LogsRunner`（Task 6 再改成 `LogStream`）。删除 `cli/SpecCommands.java`。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 190 tests, 0 failures；再跑 `MVN -Pe2e verify` → Expected: 190 + 10 e2e 全绿（证明 CLI 未改行为）。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "refactor: 新增 core.ServicePackage，CLI 改为经门面调用"
```

---

### Task 6: 日志改为 LogStream，CLI logs 迁移

**Files:**
- Create: `logs/LogSink.java`、`logs/LogStream.java`、`logs/FileLogStream.java`、`logs/JournaldLogStream.java`、`core/LogSourceException.java`
- Modify: `core/ServicePackage.java`（新增 `logs(int)`）、`cli/LogsCommand.java`
- Delete: `logs/LogsRunner.java`
- Create: `src/test/java/io/xac/systemd/logs/FileLogStreamTest.java`、`JournaldLogStreamTest.java`
- Modify: `src/test/java/io/xac/systemd/logs/LogsRunnerTest.java` → 重命名/改写为 `LogsCommandTest.java`

**Interfaces:**
```java
public interface LogSink { void accept(String line); }

public interface LogStream extends AutoCloseable {
    void start(int lines, LogSink sink);   // 阻塞到 close() 或来源结束
    void close();                          // 幂等，可从任意线程调用
}

public final class LogSourceException extends RuntimeException {
    public LogSourceException(String message);
}

public LogStream logs(int lines);   // ServicePackage
```

- [ ] **Step 1: 写失败测试**

`FileLogStreamTest`：
```java
@Test void rejectsNonPositiveLineCount()   { /* start(0) 抛 LogSourceException，消息含 "-n must be a positive integer" */ }
@Test void reportsMissingFile()            { /* 消息以 "log file not found: " 开头 */ }
@Test void streamsInitialTailThenAppends() { /* 后台线程 start(2, sink)；等 2 行；append；等新行；close() 后线程退出 */ }
```

`JournaldLogStreamTest`（用 `RecordingRunner`，不真跑 journalctl）：
```java
@Test void startsJournalctlWithFollow() { /* startedCalls().get(0) equals [journalctl,-u,xac.demo.service,--no-pager,-n,10,--follow] */ }
@Test void pumpsStdoutAndStderrLines()  { /* 两路都进 sink，顺序按到达；close() 调用 destroy() */ }
@Test void wrapsStartFailure()          { /* runner.start 抛 IllegalStateException → LogSourceException，消息原样 */ }
```

`LogsCommandTest`：把原 `LogsRunnerTest` 的 4 条 CLI 相关断言（非正 `-n`、缺文件、journal 非 follow 输出、follow 退 0）保留，全部从命令层断言。

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=FileLogStreamTest`
Expected: COMPILATION ERROR —— `FileLogStream` 不存在。

- [ ] **Step 3: 最小实现**

- `FileLogStream(Path file, long pollMillis)`：构造时判定存在/可读，否则抛 `LogSourceException`；`start` 用 `FileTail` 循环，`close` 置停止标志并 `FileTail.requestStop()`。
- `JournaldLogStream(CommandRunner runner, ServiceSpec spec)`：`start` 组 argv（`journalctl -u <unit> --no-pager -n N --follow`），`RunningCommand cmd = runner.start(argv)`，起两个守护线程按行读到 `sink`；`close` 幂等 `cmd.destroy()` 并关闭流。`start` 抛 `IllegalStateException` 时包成 `LogSourceException(e.getMessage())`。
- `ServicePackage.logs(int lines)`：`lines <= 0` 抛 `LogSourceException("-n must be a positive integer")`；`spec.logFile != null` → `new FileLogStream(...)`，否则 `new JournaldLogStream(runner, spec)`。
- `LogsCommand`：`try (LogStream stream = pkg.logs(lines))`，非 follow 时用一个收集 `StringBuilder` 的 sink，`start` 返回后打印（保留末尾补换行逻辑）；follow 时 sink 直接 `System.out.println`；`LogSourceException` → stderr + 退 2。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 约 195 tests, 0 failures。真机手工（Linux 容器或本机 systemd）跑 `logs -n 2` 与 `logs -f` 各一次，确认 Ctrl-C 能退出。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "refactor: 日志改为 core.LogStream，CLI 经门面读取"
```

---

### Task 7: TUI 骨架（依赖、命令、画布、退出）

**Files:**
- Modify: `pom.xml`（Lanterna 3.1.5）
- Create: `tui/Canvas.java`、`tui/Display.java`、`tui/LanternaDisplay.java`、`tui/KeyEvent.java`、`tui/NavKey.java`、`tui/KeySource.java`、`tui/LanternaKeySource.java`、`tui/TuiState.java`、`tui/TuiApp.java`、`tui/TuiCommand.java`
- Modify: `cli/RootCommand.java`（注册 `tui`）
- Create: `src/test/java/io/xac/systemd/tui/CanvasTest.java`、`TuiAppTest.java`、`RecordingDisplay.java`、`ScriptedKeySource.java`
- Modify: `src/test/java/io/xac/systemd/e2e/SmokeIT.java`（新增非 TTY 用例）

**Interfaces:**
```java
public final class Canvas {
    public Canvas(int width, int height);
    public int width(); public int height();
    public void clear();
    public void text(int x, int y, String s);           // 超宽截断，不换行
    public void hline(int y, char c);
    public String line(int y);                          // 测试断言用
}

public interface Display { void draw(Canvas canvas); }

public interface KeySource { KeyEvent next(long timeoutMillis) throws IOException; }  // 超时返回 null

public enum NavKey {
    CHAR, UP, DOWN, PGUP, PGDN, TOP, BOTTOM, ENTER, ESC, TAB, BACKSPACE,
    APPLY, RESTART, DESTROY, EDIT, HELP, QUIT, REFRESH, FOLLOW, YES, NO
}
public final class KeyEvent { public final NavKey key; public final char ch; }

public final class TuiApp implements Runnable {
    TuiApp(ServicePackage pkg, Display display, KeySource keys);
    public void run();          // 主循环
    void tick();                // 测试用：处理一个事件 + 渲染
    boolean running();
    String statusNote();
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void drawsTitleStatusAndKeyBar() {
    TuiApp app = newApp(80, 24, new KeyEvent(QUIT, '\0'));
    app.tick();
    assertEquals("┌ xac.demo " + repeat('─', 60) + " running · auto-start on · root ┐", display.line(0));
    assertTrue(display.line(23).contains("q quit"));
}

@Test void quitKeyStopsTheLoop() { app.tick(); assertFalse(app.running()); }
@Test void escapeAlsoQuits() { }
@Test void narrowScreenHidesKeyBar() { /* 60 列 → 键位条只剩 "? help" */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=TuiAppTest`
Expected: COMPILATION ERROR —— `io.xac.systemd.tui` 不存在。

- [ ] **Step 3: 最小实现**

- `pom.xml` 加 `com.googlecode.lanterna:lanterna:3.1.5`（compile）。
- `Canvas`：`char[][] cells`，`text` 逐字符写入，越界跳过；空单元格为 `' '`。
- `LanternaDisplay`：`Terminal` + `Screen`，`draw` 时 `screen.clear()`，按行列 `setCharacter`，最后 `refresh()`；`TuiCommand` 用 `DefaultTerminalFactory().createTerminal()`，`try/finally` 关闭。
- `LanternaKeySource`：`terminal.pollInput(timeout)` → `KeyEvent`（`KeyType.ArrowUp` → UP 等；`Character` → CHAR）。
- `TuiApp`：状态机字段 `running`、`overlay`、`status`；`tick` 取一个事件（测试用注入的 `ScriptedKeySource`），`QUIT`/`ESC` 停；渲染 → `display.draw(canvas)`。
- `TuiCommand`：非 TTY（`System.console() == null`）→ stderr `tui requires an interactive terminal` + 退 2；`ServicePackage.open` 失败 → 打印 violations + 退 2；正常 `new TuiApp(...).run()` 退 0。
- e2e：`SmokeIT` 新增 `tuiRequiresTerminal`，容器内 `java -jar xac.jar tui /opt/demo`（无 TTY）断言退出 2 且 stderr 含该行。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 约 200 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: TUI 骨架（Lanterna 画布、键位、tui 命令）"
```

---

### Task 8: 状态区与日志区

**Files:**
- Create: `tui/StatusView.java`、`tui/LogPane.java`
- Modify: `tui/TuiApp.java`（状态轮询、日志线程、滚动与跟随）
- Create: `src/test/java/io/xac/systemd/tui/StatusViewTest.java`、`LogPaneTest.java`
- Modify: `TuiAppTest.java`

**Interfaces:**
```java
public final class StatusView {
    public static List<String> lines(StatusReport report, boolean root);
}
public final class LogPane {
    public void append(String line);                 // 线程安全，保留 2000 行
    public List<String> visible(int height, boolean follow);
    public void scrollUp(int n); public void scrollDown(int n);
    public void toTop(); public void toBottom();
    public boolean following(); public void toggleFollow();
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void rendersUnitEnvProcessLinks() { /* 4 行，含 "unit    in sync"、"env     in sync"、"process active"、"links   ok" */ }
@Test void marksDriftAndUnknown() { /* systemctl 查询失败 → "process unknown"；漂移行带 "!" 前缀 */ }
@Test void logPaneKeepsTailAndFollows() { /* 追加 5 行，height=2 follow → 后两行；scrollUp 后固定 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=StatusViewTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

- `StatusView.lines`：由 `StatusResult` 派生，宽度自适应（标签列 8）；`drift` 非空的行前加 `! `；`queryOk=false` → `process unknown`；非 root 在标题栏由 `TuiApp` 加 `not root`。
- `LogPane`：`ArrayDeque<String>` 上限 2000；`visible` 按 `follow` 取尾 `height` 行，否则按 offset 取窗口。
- `TuiApp`：`lastStatusAt`；每 `tick` 若 `now - lastStatusAt > 2000` 且 `overlay == NONE` → `pkg.status()`；日志线程在构造时启动（`pkg.logs(200).start(200, line -> { pane.append(line); dirty = true; })`），`run()` 的 `finally` 关闭；键位 `j/k/PgUp/PgDn/g/G` 调 `LogPane`，`L` 切 follow，`R` 立即刷新状态。日志区高度 = 屏高 − 7，最小 0；`< 20` 行时隐藏，`l` 切换。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 约 205 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: TUI 状态区与日志区（轮询、滚动、跟随）"
```

---

### Task 9: TUI 动作（apply 预览、restart、destroy 确认）

**Files:**
- Create: `tui/actions/ActionOverlay.java`、`tui/actions/ActionKind.java`
- Modify: `tui/TuiApp.java`、`core/ServicePackage.java`（新增 `restart()`）
- Create: `src/test/java/io/xac/systemd/tui/ActionOverlayTest.java`
- Modify: `TuiAppTest.java`

**Interfaces:**
```java
public enum ActionKind { APPLY, RESTART, DESTROY }
public final class ActionOverlay {
    public static ActionOverlay applyPlan(List<StepPlan> plan);
    public static ActionOverlay destroyPlan(DestroyOutcome plan);
    public static ActionOverlay restartConfirm(String name);
    public ActionKind kind();
    public List<String> lines();          // 面板内容
    public boolean accepts(NavKey key);   // APPLY/restart: ENTER/YES；DESTROY: 仅 YES
    public String hint();                 // 底部提示，含 "enter" / "y" 的区别
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void applyOverlayNeedsEnter() { /* ENTER true、YES false；apply 面板含 7 行 */ }
@Test void destroyOverlayOnlyAcceptsY() { /* ENTER false、YES true、hint 含 "y" */ }
@Test void enterOnDestroyDoesNothing() { /* TuiApp: 按 d 后 ENTER → destroy 未被调用（RecordingRunner calls 空） */ }
@Test void applyExecutesOnlyAfterConfirmation() { /* a 后无调用；ENTER 后 steps 全 ok */ }
@Test void nonRootDisablesExecution() { /* probe.isRoot=false → hint 含 "root"，ENTER 不执行 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=ActionOverlayTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

- `TuiApp` 状态：`overlay` 增加 `ACTION` + `ActionOverlay` 实例；`a/r/d` 创建覆盖层（`a` 调 `pkg.plan()`；`d` 调 `pkg.destroyPlan()` 取计划——纯读计划，不落盘）。
- 执行：apply → `pkg.apply(false)`；restart → 新增的 `pkg.restart()`（`Preflight.check` + `RestartAction.run`，结果包装成单个 `StepResult`）；destroy → `pkg.destroy(false)`。结果面板显示 `ok/failed` 与 warnings；失败停在该步。
- `ServicePackage.restart()`：
```java
public StepResult restart() throws InvalidSpecException {
    requirePreflight(Preflight.check(spec, probe, runner, target));
    RestartAction action = new RestartAction();
    ApplyContext ctx = new ApplyContext(spec, target, runner, false);
    try {
        action.run(ctx);
        return new StepResult(new StepPlan(action.describe(ctx), false), true, null);
    } catch (ActionFailure e) {
        return new StepResult(new StepPlan(action.describe(ctx), false), false, e.getMessage());
    }
}
```
- 执行期间 `busy=true`，其它动作键忽略；完成后 `status` 立即刷新。
- 非 root：面板可打开，`accepts` 返回 false，`hint` = `apply requires root`。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 约 210 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: TUI 动作面板（apply 预览/restart/destroy 二次确认）"
```

---

### Task 10: SpecWriter（规范化输出）

**Files:**
- Create: `spec/SpecWriter.java`
- Create: `src/test/java/io/xac/systemd/spec/SpecWriterTest.java`

**Interfaces:**
```java
public final class SpecWriter {
    public static String write(ServiceSpec spec);                     // 无注释，纯结构
    public static String write(ServiceSpec spec, Comments comments);  // 带保留注释
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void writesKeysInFixedOrderAndEndsWithSingleNewline() {
    String yaml = SpecWriter.write(spec);
    assertTrue(yaml.startsWith("name: demo\n"));
    assertTrue(yaml.endsWith("\n") && !yaml.endsWith("\n\n"));
    assertTrue(yaml.indexOf("\nartifact:") < yaml.indexOf("\nruntime:"));
}

@Test void quotesValuesWhenNeeded() { /* 含 ":" 或 "#" 或首尾空格 → 双引号，内部 " 转义 */ }
@Test void omitsEmptySections() { /* env 空则不写 env:；logs 为空不写；lifecycle 缺省值写默认 */ }
@Test void roundTripsThroughSpecLoader() { /* 写入临时文件 → SpecLoader.load → 字段等价 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=SpecWriterTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

按模板顺序输出：`name`、`description`（非 null）、`artifact.jar`、`runtime.java` + `opts`（非空）、`env`（非空，按插入序）、`properties`（非空）、`workdir`（非 null）、`path-links`（非空）、`logs.file`（非 null）、`lifecycle` 四键（恒写，使用 `OrDefault()`）。标量引用规则：含 `: `、` #`、以空格开头/结尾、为空 → `"` 包裹并把 `"`→`\"`、`\`→`\\`；否则原样。列表项 `- <scalar>`；map 键值同规则。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test -Dtest=SpecWriterTest` → Expected: 4 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: SpecWriter 规范化输出 xac.yaml"
```

---

### Task 11: 注释扫描与挂回

**Files:**
- Create: `spec/Comments.java`、`spec/YamlCommentScanner.java`
- Modify: `spec/SpecWriter.java`（消费 `Comments`）
- Create: `src/test/java/io/xac/systemd/spec/YamlCommentScannerTest.java`

**Interfaces:**
```java
public final class Comments {
    public final Map<String, String> inline;              // path → 同行注释（不含 #）
    public final Map<String, List<String>> leading;       // path → 上方注释行（不含 #）
    public final List<String> orphans;                    // 无法挂回的注释（含行号前缀）
}

public final class YamlCommentScanner {
    public static Comments scan(String text);
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void keepsInlineCommentOnScalar() { /* "name: demo # 服务名" → inline["name"]="服务名" */ }
@Test void keepsLeadingBlockAboveField() { }
@Test void keepsListValueComment() { /* "- ./conf:/opt/conf  # 配置" → inline["path-links[./conf:/opt/conf]"] */ }
@Test void ignoresHashInsideQuotes() { /* description: "a # b" 不产生注释 */ }
@Test void reportsOrphans() { /* 文件末尾游离注释进 orphans */ }
@Test void writerReplaysComments() { /* write(spec, comments) 输出含注释且再 scan 一致 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=YamlCommentScannerTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

逐行扫描：维护 `Deque<IndentNode>`（缩进 → 路径段）；空行清空“待挂”注释块；行内 `#` 只有在引号外且前面是空白时才算注释；`- ` 开头按值生成 `key[value]` 段。`SpecWriter` 在每行前输出 `leading`，行尾输出 ` # inline`。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test -Dtest=YamlCommentScannerTest` → Expected: 6 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: YAML 注释扫描与按路径保留"
```

---

### Task 12: 编辑器模型（字段树、草稿、校验定位）

**Files:**
- Create: `tui/edit/EditorField.java`、`tui/edit/EditorModel.java`
- Create: `src/test/java/io/xac/systemd/tui/edit/EditorModelTest.java`

**Interfaces:**
```java
public final class EditorField {
    public final String path;          // "runtime.opts"
    public final Schema.Type type;
    public final boolean readOnly;
    public final List<String> values;  // 标量 1 个；列表 N 个；map "k=v"
    public final List<String> options;// 枚举
    public final String label;
}

public final class EditorModel {
    public static EditorModel from(ServiceSpec spec, Comments comments);
    public List<EditorField> fields();
    public EditorField field(String path);
    public EditorModel with(String path, List<String> values);   // 不可变副本
    public ServiceSpec toSpec();                                  // 非法时抛 IllegalArgumentException
    public List<Violation> validate();                            // ValidationRunner + 字段级定位
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void exposesAllEditableFieldsInFixedOrder() { /* 13 个字段，name 只读 */ }
@Test void mapsListsAndMapsBackToSpec() { /* opts 两行、env 两行 → toSpec 的字段一致 */ }
@Test void validatesPlaceholderReferences() { /* properties 引用未定义 env → validate 含占位符 violation 与字段路径 */ }
@Test void rejectsMalformedMapRow() { /* "novalue" → IllegalArgumentException，消息含行号 */ }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=EditorModelTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

字段顺序：`name`、`description`、`artifact.jar`、`runtime.java`、`runtime.opts`、`env`、`properties`、`workdir`、`path-links`、`logs.file`、`lifecycle.auto-start`、`lifecycle.restart`、`lifecycle.restart-sec`、`lifecycle.timeout-stop-sec`。布尔用 `""`/`true`/`false` 表示“缺省”；枚举第一项 `""` 表示缺省。`toSpec` 用 `ServiceSpec.builder(packageDir)` 逐字段；`env`/`properties` 用 `LinkedHashMap` 保序；行 `k=v` 以第一个 `=` 切分。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test -Dtest=EditorModelTest` → Expected: 4 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 编辑器字段树与草稿模型"
```

---

### Task 13: 编辑表单 UI 与保存流程

**Files:**
- Create: `tui/edit/EditScreen.java`、`tui/edit/EditorView.java`、`tui/edit/SaveFlow.java`、`spec/SpecSaver.java`、`spec/LineDiff.java`
- Modify: `tui/TuiApp.java`（`e` 打开，`ESC` 返回）
- Create: `src/test/java/io/xac/systemd/tui/edit/EditScreenTest.java`、`SaveFlowTest.java`、`spec/LineDiffTest.java`

**Interfaces:**
```java
public final class LineDiff {
    public static List<String> diff(String oldText, String newText);   // "  x" / "- x" / "+ x"
}
public final class SpecSaver {
    public static void save(Path file, String text) throws IOException; // 同目录临时文件 + 0644 + ATOMIC_MOVE
}
public final class SaveFlow {
    public static Result prepare(EditorModel model, Path yamlFile);
    public static void commit(Path yamlFile, String text) throws IOException;
    public static final class Result { List<Violation> violations; String newText; List<String> diff; List<String> orphanNotes; }
}
public final class EditScreen {
    EditScreen(EditorModel model, Comments comments);
    void handle(NavKey key, char ch);
    List<String> lines(int width, int rows);
    boolean dirty(); boolean closed(); EditorModel model();
}
```

- [ ] **Step 1: 写失败测试**

```java
@Test void tabMovesBetweenSectionsAndArrowsBetweenFields() { }
@Test void enterEditsScalarAndCommitUpdatesModel() { /* 覆盖 "description" */ }
@Test void spaceTogglesBooleanAndC() { /* auto-start 三态循环：缺省→true→false→缺省 */ }
@Test void arrowOnListEntersRowEditorAndAAddsRow() { }
@Test void saveWithErrorsStaysAndListsViolations() { /* 删掉 name 之外的必填 → validate 非空，closed=false */ }
@Test void saveWritesAtomicallyAndReportsDiff() { /* 临时目录写 yaml；commit 后文件内容 == newText；diff 有 + 行 */ }
@Test void detectsExternalModification() { /* prepare 后改 mtime/size → Result 带 "modified outside" 提示 */ }
@Test void rollbackRestoresPreviousText() { }
```

- [ ] **Step 2: 跑失败**

Run: `MVN test -Dtest=EditScreenTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 最小实现**

- `EditorView`：渲染字段行（path 左、值右，截断），底部两行提示 `enter edit  s save  u undo  q cancel`，错误行标红（Canvas 不带颜色时用 `!` 前缀）。
- `EditScreen`：`focus` 整数、`mode {BROWSE, EDIT_SCALAR, EDIT_LINES}`；标量编辑复用一个 `StringBuilder` 缓冲（`CHAR` 追加、`BACKSPACE` 删、`ENTER` 提交、`ESC` 取消）；列表/map 进入 `EDIT_LINES`：`↑↓` 选行、`a` 追加空行、`x` 删行、`ENTER` 编辑该行为标量、`ESC` 返回。
- `SaveFlow.prepare`：`model.validate()` → 错误直接返回；否则 `SpecWriter.write(model.toSpec(), comments)` → `LineDiff.diff(old, new)` → 比较 `mtime+size` 生成提示；`commit` 调 `SpecSaver.save`。
- `TuiApp`：`e` 进入编辑（先 `ServicePackage.open` 已持有 spec；同时读原文件文本与注释），编辑中暂停状态轮询；保存成功 → 重新 `ServicePackage.open` 重建状态与日志（`LogStream` 重建）；`q` 脏数据二次确认（`y` 放弃）。

- [ ] **Step 4: 跑测试确认通过**

Run: `MVN test` → Expected: 约 230 tests, 0 failures。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: TUI 结构化编辑与规范化保存"
```

---

### Task 14: 打包、文档、版本 0.2.0

**Files:**
- Modify: `pom.xml`（版本 `0.2.0`）、`cli/RootCommand.java`（`version = "0.2.0"`）、`pom.xml` 的 shade 配置注释
- Modify: `README.md`、`AGENTS.md`、`CONTRIBUTING.md`、`docs/superpowers/specs/2026-11-13-tui-and-layering-design.md`（补「实现落点」小节）
- Create: `e2e/` 无新增；`SmokeIT` 加 terminfo 冒烟

- [ ] **Step 1: 写失败测试**

`SmokeIT` 新增 `tuiLoadsLanternaTerminfo()`：容器内 `java -jar xac.jar tui /opt/demo`（非 TTY）→ 退 2 且 stderr 是 `tui requires an interactive terminal`（若 terminfo 缺失，Lanterna 会在创建终端时抛异常并打印堆栈，此断言即失败）。

- [ ] **Step 2: 跑失败**

Run: `MVN -Pe2e verify -Dtest=SmokeIT#tuiLoadsLanternaTerminfo`
Expected: 若 shade 过滤了 terminfo，FAILED 且 stderr 含 `NoSuchFileException` 或 `terminfo`。

- [ ] **Step 3: 实现**

- `pom.xml`：版本 `0.2.0`；shade 的 `filters` 里**不得**出现排除 `**/terminfo/**`（当前无 filters，保持无）；`ManifestResourceTransformer` 不变。
- 全仓替换版本号 `0.1.3` → `0.2.0`（`cli/RootCommand.java`、`README.md`、`AGENTS.md`、`CONTRIBUTING.md`、`.github/workflows/publish.yml`、`.github/scripts/sync-gitcode-release.ps1`）；历史计划/设计文档不改。
- `AGENTS.md`：分层纪律（`cli → core`、`tui → core`、`spec/systemd/exec` 不反向依赖）；依赖白名单加 Lanterna；命令段加 `tui`；「TUI 仅 Linux/SSH」。
- `README.md`：新增「TUI」一节（启动、键位表、截图式块、非 TTY 说明、编辑与保存的安全行为）。
- `CONTRIBUTING.md`：测试数改为实际值；TUI 测试不启动终端的说明。
- 设计文档：每节末尾补一行「实现落点：`tui/...`」。

- [ ] **Step 4: 跑全量验证**

```bash
MVN clean test          # Expected: 约 230 tests, 0 failures（16 skipped 允许）
MVN -Pe2e verify        # Expected: 230 + 11 e2e，BUILD SUCCESS
MVN -DskipTests package # Expected: target/java-systemd-service-cli-0.2.0.tar.gz
```

`tar -tzf target/java-systemd-service-cli-0.2.0.tar.gz` 确认 `bin/`、`libs/` 存在；解压后 `java -jar libs/*.jar --version` 输出 `0.2.0`；`jar tf` 里能看到 `com/googlecode/lanterna/...`。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "build: 发布 0.2.0（TUI 与三层分层）"
```

---

## Self-Review

**1. Spec 覆盖**

| 设计文档 | 落点 |
|---|---|
| §4.1 包结构 | Task 5/6/7 建立 `core`、`tui`；`logs/LogsRunner` 删除 |
| §4.2 依赖规则 | Task 5 后 CLI 只经 facade；Task 14 写进 `AGENTS.md` |
| §4.3 四处违规 | Task 2（format）、3（ApplyRunner）、4（DestroyRunner）、6（LogsRunner + SpecCommands 删除） |
| §5 facade 七方法 | Task 5（六方法）+ Task 6（`logs`）+ Task 9（`restart` 补充） |
| §5.1 日志流 | Task 1（RunningCommand）+ Task 6 |
| §6.1 命令与启动 | Task 7（非 TTY 退 2，e2e 覆盖） |
| §6.2 画面 / §6.3 键位 | Task 7/8/9/13 |
| §6.4 线程 | Task 8（状态 2s、日志线程）、Task 9（busy） |
| §6.5 状态呈现 | Task 8（StatusView + root 标记） |
| §6.6 动作 | Task 9（apply 预览、destroy 仅 y、restart 确认） |
| §7 编辑 | Task 10/11/12/13 |
| §8 测试 | 每个 Task 的测试步骤；Task 14 e2e |
| §9 交付 | Task 14（版本、terminfo、文档） |

**2. 占位符扫描**：本文件不含 `TBD`/`TODO`/`implement later`；所有代码块给出可直接编译的签名或方法体片段。

**3. 类型一致性**：
- `StepResult.message` 承担两种语义：失败原因（`ok=false`）、完成但有警告（`ok=true`）——Task 4 的 CLI 用 `ok` 区分，避免再加字段。
- `ServicePackage.apply/destroy` 抛 `InvalidSpecException`（预检失败），运行期失败走 `outcome.failed`——CLI 分别映射 2 与 1。
- `LogStream.start` 阻塞语义与 `LogsCommand` 的 try-with-resources 一致；`close()` 幂等，供日志线程与 TUI 退出共用。
- `NavKey` 由按键适配层统一，`TuiApp` 与 `EditScreen` 只认 `NavKey`，测试不需要 Lanterna。

**4. 与设计文档的已知偏差（需在实现时知晓）**
- 设计文档 §5 的 `ServicePackage` 未列 `restart()`；为实现 TUI `r` 且不重建 unit，Task 9 增加该方法（只做 preflight + `RestartAction`），理由：`apply` 会重写 unit/env，语义过重。
- 设计文档 §7.1 的列表/映射控件描述为「行列表 / 键值表」；Task 13 用统一的「多行子编辑器」实现（`a` 加行、`x` 删行、`enter` 编辑行），交互等价但实现更小。
- 设计文档说 `apply` 在 TUI 中「逐步显示 pending → ok/failed」；Task 9 先执行后整体显示（`ApplyOutcome` 是批量的），逐帧推进留待后续；不影响安全语义。

**5. 遗留风险**
- Task 6 把 journald follow 从 `inheritIO` 改成子进程泵行，Ctrl-C 行为依赖 `JournaldLogStream.close()` 的 shutdown hook；需在真机手工验证一次（Task 6 Step 4）。
- Task 13 的注释保留是尽力而为，`SpecWriter` 输出与手写文件的空白/引号风格可能不同；diff 面板是唯一确认点。

## Execution Handoff

两种执行方式：

1. **Subagent-Driven（推荐）**：每个 Task 派一个实现子代理，完成后派评审子代理（spec ✅/❌ + 质量），有 Critical/Important 就修复再审；我只做派工、裁定与记账。
2. **Inline**：我在当前会话里按 Task 顺序实现，每个 Task 写完测试再写代码，跑绿提交，中途向你汇报。

请回复 `1` 或 `2`（也可以指定从哪个 Task 开始）。
