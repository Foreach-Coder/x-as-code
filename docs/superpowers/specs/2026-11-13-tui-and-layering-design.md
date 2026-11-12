# TUI 与三层分层设计

日期：2026-11-13
状态：待评审
范围：`java-systemd-service-as-code`（v0.2.0）

## 1. 背景

CLI 已经有 `apply / status / logs / destroy / init / install / uninstall`，形态是"一条命令一个动作、输出一段文本"。但它有两个结构性缺口：

1. **没有稳定的核心层**。`ApplyRunner.run(ctx, PrintStream out)`、`DestroyRunner.run(ctx, PrintStream out)` 直接决定文案并返回退出码；`LogsRunner` 硬编码 `System.out`；"加载 + 校验 + 预检"这套编排在 `cli/SpecCommands` 里。任何第二个入口（TUI、HTTP、以后的 GUI）都只能复制这套逻辑或反向依赖 CLI。
2. **没有交互式的管理面**。运维在服务器上想知道"现在什么状态、日志在说什么、要不要 apply"，只能反复敲三条命令。

本设计同时解决这两件事，并且**先做分层、再做 TUI**：TUI 是分层的第一个受益者与验证者，不是目的本身。

## 2. 非目标

- 不做多包/多服务面板。一个 TUI 实例管一个包，与"一个 `xac.yaml` 一个服务"一致。将来要做多包，是新设计，不预留接口。
- 不做原文模式编辑器（raw text editor）。编辑基于结构化模型。
- 不改变 DSL。`xac.yaml` 的字段、校验、渲染规则一律不动。
- 不支持 Windows 作为 TUI 平台：以 Linux/SSH 为主。Windows 上能编译、单测能跑，但不保证 TUI 可用，也不为此加分支。
- 不做远程编排（连接远端主机）。TUI 与 CLI 一样在本机执行。

## 3. 关键决策

| 编号 | 决策 | 理由 |
|---|---|---|
| D1 | 引入三个明确层次：**core（能力与编排）→ cli / tui（入口与呈现）** | 两个入口必须共用同一份判断，否则必然分叉 |
| D2 | 新增 `core` 包，以 `ServicePackage` 为唯一门面 | 把散在 CLI 命令里的编排收口 |
| D3 | `core` 不出现 `System.out/err`、`PrintStream`、退出码、中文用户文案；允许返回英文的**事实性步骤描述**（`StepPlan.description`） | 呈现属于入口层；core 只返回数据与事实，入口层决定是否逐字显示 |
| D4 | `cli` 与 `tui` 互不依赖，都只依赖 `core` | 保证任一入口可独立演进、独立测试 |
| D5 | TUI 用 Lanterna 3.1.5 | 纯 Java、JRE 8+、无原生依赖，与 fat jar 部署模型一致 |
| D6 | TUI 管单包 | 与 D3（单服务）一致；多包会改变产品范围 |
| D7 | TUI 提供全生命周期动作：apply / restart / destroy | 少了 destroy 反而促使运维退出去手打，失去确认保护 |
| D8 | apply 在 TUI 里**先预览后执行** | apply 会重启生产服务，不能一键直落 |
| D9 | destroy 确认只认 `y`，`Enter` 不生效 | 防手滑；比 CLI 的 `y/N` 更严 |
| D10 | 编辑是 Schema 驱动的结构化表单，保存时**规范化重写** | 解析器无法保留注释，规范化输出可预测、diff 干净 |
| D11 | 规范化重写时**按路径保留注释**（同行、前置、列表项、段头） | env/opts/properties/path-links 是注释最密集处，丢注释等于丢信息 |
| D12 | 保存不自动备份；`diff` 是唯一确认点；会话内保留上一版可回滚 | 与 `AGENTS.md` "禁止自动化地移动或删除用户数据"一致 |
| D13 | `name` 在编辑器里只读 | 改名等于换服务（新 unit、新 env），应新建包 |
| D14 | 非 root 启动 TUI：只读可用，动作禁用 | `status`/`logs`/`edit` 不需要 root |
| D15 | 版本号 `0.2.0` | 新增子系统，非修复 |

## 4. 分层

### 4.1 包结构

```
io.xac.systemd
├── spec/      解析、Schema、校验、模型、YamlCommentScanner、SpecWriter
├── systemd/   UnitRenderer、UnitNaming、SystemdTarget、SystemctlShow
├── exec/      CommandRunner、ProcessRunner、ExecResult、RunningCommand
├── apply/     Action 与七个动作、ApplyContext、Preflight、FileWriter
├── status/    StatusRunner、StatusResult（只留数据与判断）
├── logs/      LogStream、FileLogStream、JournaldLogStream、FileTail
├── core/      ServicePackage ← 唯一门面
├── cli/       picocli 命令 + Formatter（全部文案与退出码）
└── tui/       Lanterna 画面、键位、视图模型
```

### 4.2 依赖规则

- `cli → core`、`tui → core`；`core → spec/systemd/exec/apply/status/logs`。
- `cli` 与 `tui` 之间零依赖。
- `spec/systemd/exec` 不依赖 `apply/status/logs/core/cli/tui`。
- 反向依赖由评审把关；`AGENTS.md` 增补这条纪律。

### 4.3 现状违规（本次必须清掉）

| 位置 | 问题 |
|---|---|
| `apply/ApplyRunner.java:29` | `run(ApplyContext, PrintStream)`，边执行边打 `would …`/`失败: …`，并返回退出码 |
| `apply/DestroyRunner.java:34` | 同上，且确认清单文案在 core 层生成 |
| `cli/SpecCommands.java` | 编排（load + validate + preflight）在 CLI 层，TUI 无法复用 |

### 4.4 `StatusResult` 的调整

`format()` 属于呈现，从 `status` 包移出到 `cli/StatusTextFormatter`；`StatusResult` 只保留数据与 `drift()` 判断。CLI 输出逐字节不变（现有测试即契约）。

对照检查由 TUI 自己渲染，不复用 CLI 的文本。

## 5. `ServicePackage` 门面

```java
package io.xac.systemd.core;

public final class ServicePackage {
    public static ServicePackage open(Path packageDir) throws InvalidSpecException;
    // 注入依赖的入口：CLI 与测试用它替换 systemd / root / 命令执行。
    public static ServicePackage open(Path packageDir, CommandRunner runner,
                                      EnvironmentProbe probe, SystemdTarget target)
            throws InvalidSpecException;
    public ServiceSpec spec();
    public Path packageDir();
    public boolean isRoot();
    public StatusReport status();
    public List<StepPlan> plan();
    public ApplyOutcome apply(boolean dryRun) throws InvalidSpecException;
    public StepResult restart() throws InvalidSpecException;
    public DestroyOutcome destroyPlan() throws InvalidSpecException;
    public DestroyOutcome destroy(boolean dryRun) throws InvalidSpecException;
}
```

语义：

- `open`：`SpecLoader` + `ValidationRunner`。不碰 systemd、不要求 root、不改文件。失败抛 `InvalidSpecException(List<Violation>)`。注入版只替换依赖，语义不变。
- `isRoot`：透传 `EnvironmentProbe.isRoot()`，供 CLI/TUI 提前提示，不改变任何动作的前置条件。
- `status`：每次真实查询（`systemctl show` + 读文件 + lstat 链接），不缓存。
- `plan`：`apply` 的七个步骤描述，纯数据，不执行、不做预检。
- `apply(true)`：预检 + 七步描述，不落盘、不执行命令；`apply(false)`：预检 + 执行，首个失败即停。
- `destroyPlan`：只读计划，预检后只读文件系统、零动作，供确认面板使用；TUI 取 destroy 计划必须用它，**不要**用 `destroy(true)` 代替。
- `destroy(true/false)`：返回删除清单、保留项、步骤结果、警告；`Stop/Disable/Reload` 的失败记入 `warnings` 而不是中止。
- `destroyPlan(DestroyRunner)` / `destroy(boolean, DestroyRunner)`：供 CLI/测试注入动作序列；生产默认用无参重载。
- 日志门面：`LogStream logs(int lines)`（非 follow，CLI 默认）与 `LogStream logs(int lines, boolean follow)`（`-f`）。`start(...)` 阻塞到来源结束或 `close()`，调用方必须放在工作线程；`close()` 幂等，可能等待子进程退出。

返回类型（全部为不可变数据）：

```java
public final class StepPlan     { String description; boolean destructive; }
public final class StepResult   { StepPlan plan; boolean ok; String message; }
public final class ApplyOutcome { List<StepResult> steps; StepResult failed; List<String> warnings; boolean performed; }
public final class DestroyOutcome { List<String> willRemove; List<String> willKeep; List<StepResult> steps; List<String> warnings; }
public final class StatusReport { StatusResult status; List<String> drift; boolean healthy; }
```

`StepPlan.description` 是英文的事实描述（如 `link /srv/a -> /opt/a`、`systemctl restart xac.demo.service`），不含“请”“失败于”这类措辞；CLI 与 TUI 都可以直接显示或自行加工。错误消息（`StepResult.message`、`warnings`）同理：描述发生了什么，不负责排版。

### 5.1 日志流

```java
public interface LogSink  { void accept(String line); }
public interface LogStream extends AutoCloseable {
    void start(int lines, LogSink sink);  // 阻塞到 close() 或来源结束
    default void start(int lines, LogSink stdoutSink, LogSink stderrSink) {
        start(lines, stdoutSink);         // 合并两路；journald 覆写以保留 stderr 通道
    }
    boolean isFollow();
    void close();                          // 幂等，可能等待子进程
}
```

两个实现：

- `JournaldLogStream`：`ProcessBuilder` 启动 `journalctl -u <unit> --no-pager -n N`（follow 时追加 `--follow`），stdout 与 stderr 各一个泵线程按行送入对应 sink；非 follow 时 stderr 只用于非零退出的错误消息；`close()` 先 `destroy()`，必要时 `destroyForcibly()` 兜底。follow 模式注册 shutdown hook。
- `FileLogStream`：`FileTail` + 停止标志；`close()` 置位并唤醒。follow 时最后一行没有换行符的残余只在 `follow()` 正常返回后由 `flushPending()` 补出。

`start` 是阻塞调用，TUI 要放在日志线程里；`close()` 可能等待子进程退出，不要在 UI 线程直接同步调用。TUI 把 `close()` 交给独立 daemon 线程，UI 最多等 250ms。文件 follow 没有 shutdown hook，Ctrl-C 可能丢掉最后一行未换行的残余。

`exec` 层新增 `RunningCommand`（由 `ProcessRunner.start(argv)` 返回）：暴露 `stdout()`/`stderr()` 读流、`waitFor()`、`destroy()`、`destroyForcibly()`。这是本次唯一新增的基础设施。

## 6. TUI

### 6.1 命令与启动

```
java-systemd-service-cli tui [<package-dir>]
```

- 缺省 `.`，与其它命令一致；`xac.yaml` 不存在/不合法 → 打印 violation，退 2。
- 非 TTY（`System.console() == null` 且无可用终端）→ stderr 一行提示改用非 TUI 命令，退 2。
- 正常退出退 0；`Ctrl-C` 由 Lanterna 处理后同样退 0。

### 6.2 画面

主屏 = 标题栏 + 状态区 + 日志区 + 通知行 + 键位条。

```
┌ xac.demo ──────────────── running · auto-start on · root ┐
│ unit    in sync   /etc/systemd/system/xac.demo.service   │
│ env     in sync   /etc/xac/xac.demo.env                  │
│ process active    pid 12345 · since 10:32:11             │
│ links   ok        /opt/demo/app.jar -> /srv/demo/app.jar │
├ logs ─ /var/log/demo/app.log · follow ───────────────────┤
│ …                                                         │
└ a apply  r restart  d destroy  e edit  ? help  q quit ───┘
```

覆盖层：apply 预览（七步）、restart 确认、destroy 确认（删除清单 + 保留项）、edit 表单、help。均为居中面板，只有确认键才执行，`Esc` 关闭。

窄屏（< 80 列）键位条收起为 `? help`；矮屏（< 20 行）日志区默认隐藏，`l` 切换。

### 6.3 键位

主屏：`j/k`、`↑/↓` 滚日志，`PgUp/PgDn` 翻页，`g/G` 顶/底，`L` 切跟随，`R` 立即刷新，`a/r/d/e` 动作，`?` 帮助，`q` 退出。

编辑屏：`tab/shift-tab` 切分区，`↑/↓` 选字段，`enter` 编辑或打开子编辑器，`space` 切布尔，`c` 循环枚举，`a` 加行，`x` 删行，`s` 保存，`u` 回滚上一版，`q` 放弃（脏时二次确认）。

### 6.4 线程与刷新

- UI 线程：50ms 轮询按键，脏标记才刷新；状态查询不在这里执行。
- 状态 worker：单线程 daemon 执行器，每 2 秒 `ServicePackage.status()`；UI 侧 `Future.get(3s)`，预算 3 秒，超时或异常显示 `unknown` 并 `cancel(true)`，**UI 最多阻塞 3 秒**（本轮取结果，不跨 tick 异步收）；**覆盖层/编辑中暂停提交**，关闭后立即补一次；`run()` 退出时 `shutdownNow()`。
- 动作线程：apply/restart/destroy 在独立 daemon 线程执行，UI 每 tick 检查完成标志；执行期间显示 `running...`、暂停状态轮询，完成后立即刷新状态。
- 日志线程：`LogStream.start` 阻塞；`close()` 停止；切换来源或保存成功后重建。TUI 请求 follow 模式；流正常结束置 `logsEnded`，表头显示 `follow (ended)`，通知行提示 `log stream ended`。
- 日志关闭：`close()` 放到独立 daemon 线程，UI 最多 `join(250ms)`；超时放弃等待，daemon 线程不阻塞 JVM 退出。
- 线程间只传不可变值；绘制只在 UI 线程发生，`LogPane` 的读写由自身锁保护（日志线程可 `append`）。

### 6.5 状态呈现

- 逐项显示 `unit / env / process / links`，各自带状态与原因；漂移行标红并用 `StatusResult.drift()` 的同源判断：unit 行同时覆盖 unit 文件不同步、systemd 未加载（`not-found`）与 auto-start 期望不符；env 行覆盖配置了却不同步以及未配置但文件多余；process 行覆盖查询失败与非 active；links 行覆盖链接问题。
- `systemctl` 查询失败（无 systemd、D-Bus 不可用）显示 `unknown` + 原因；不阻止退出与编辑，但禁用所有动作键。
- 非 root：标题栏标 `not root`，动作面板可打开但 `y` 不可用并提示需要 root。
- 通知行显示成功/失败/“N 条注释无法定位”，3 秒淡出；错误保留到下次动作。

### 6.6 动作

- `a`：预览面板列七步；只有 `y` 执行，`enter`/`n`/`esc` 取消；执行期间通知行显示 `running...`、暂停状态轮询，完成后 notice `apply ok` / `apply failed: <msg>` 并立即刷新状态。
- `r`：确认面板列出 `systemctl restart <unit>`；只有 `y` 执行，`enter`/`n`/`esc` 取消；notice `restart ok` / `restart failed: <msg>`。
- `d`：面板列出 willRemove/willKeep；只有 `y` 执行，`enter`/`n`/`esc` 取消（不执行，也不需要输入服务名）；notice `destroy ok` / `destroy failed: <msg>`。
- 动作在独立 daemon 线程执行，UI 不阻塞、每 tick 检查完成标志；执行期间忽略其它按键，但 `q`/`esc`/`ctrl-c` 可放弃等待并退出。放弃等待后进程即将退出：当前命令可能完成，**剩余步骤不会执行**，请重跑 `apply`/`destroy`；不提供 detached 执行。结果不再回写 UI，notice 记 `left running; current step may finish, remaining steps will not; re-run apply/destroy`；退出前不再发起状态查询，避免为一条即将消失的结果再阻塞最多 3 秒。

## 7. 编辑

### 7.1 表单生成

由 `Schema` 字段树生成，不手写界面：

| 类型 | 控件 | 字段 |
|---|---|---|
| STRING | 单行文本 | `name`（只读）、`description`、`artifact.jar`、`runtime.java`、`workdir`、`logs.file` |
| STRING_LIST | 行列表 | `runtime.opts`、`path-links` |
| STRING_MAP | 键值表 | `env`、`properties` |
| BOOLEAN | 开关 | `lifecycle.auto-start` |
| INTEGER | 数字输入 | `lifecycle.restart-sec`、`timeout-stop-sec` |
| 枚举 | 循环 | `lifecycle.restart` |
| SECTION | 分组 | `runtime`、`lifecycle`、`logs` |

`name` 行尾提示：`改名等于换服务，请新建包目录`。

### 7.2 校验

- 失焦校验：用草稿构建 `ServiceSpec`，跑 `ValidationRunner`，取路径前缀匹配当前字段的 `Violation` 显示在行尾；不阻塞编辑。
- 保存前全量校验：有错则停留，底部列错误，光标跳到第一条。
- 跨字段规则沿用现有 Validator：`${VAR}` 必须在 `env` 存在；`path-links` 目标护栏；绝对路径；换行注入拒绝。

### 7.3 保存

```
s → 草稿 spec → Schema + ValidationRunner
   ├─ 错误 → 停留、列错、跳第一条
   └─ 通过 → SpecWriter 生成规范文本（含保留注释）
        → diff 面板（逐行对比）
        → y → 临时文件 + 0644 + ATOMIC_MOVE
        → 重新 open → 状态刷新 → 通知 saved
```

安全细节：

1. 进入编辑时记录 `mtime + size`；保存前若变化，提示“文件在编辑期间被外部修改”，选择覆盖或放弃。
2. 不自动备份；会话内保留上一版文本供 `u` 回滚。
3. 保存与 apply 解耦，不提供“保存并应用”。

### 7.4 注释保留

`YamlCommentScanner` 逐行扫描原文，维护缩进路径栈，识别引号外的 `#`，产出：

- `path → inline comment`（同行注释）
- `path → List<String> leading`（紧邻上一行）

挂回规则：

| 形态 | 挂回路径 |
|---|---|
| 同行注释 | 标量：字段路径；列表项：按**值**匹配 |
| 前置注释 | 同上，输出为该字段上方若干行 |
| 段头注释 | 段路径（如 `env`） |

`SpecWriter` 按固定顺序输出（与模板一致），先用模板默认英文注释打底，再用扫描结果覆盖；`env`/`properties` 保留插入顺序，`opts`/`path-links` 保序；结尾单个 `\n`，LF。

无法定位的注释（文件末尾游离、块标量内部、多余注释块）在 diff 前提示条数，不静默丢弃。

### 7.5 限制

- 语法错误或含未知字段的 `xac.yaml` 无法构建模型 → 不能进入编辑器，需手改。
- 注释保留是尽力而为：只保留紧邻的一段。
- 编辑不做环境预检（jar 是否存在、是否 root、是否 systemd 机器）。

## 8. 测试

| 层 | 内容 | 手段 |
|---|---|---|
| core | `ServicePackage` 七类返回与副作用 | `RecordingRunner` + 临时目录 |
| 视图模型 | 状态行、漂移着色、编辑字段树、校验定位、diff 文本 | 纯 JUnit，不碰终端 |
| TUI 壳 | 键位映射、覆盖层开关、resize 不崩 | `DefaultVirtualTerminal` + 注入按键 |
| CLI 回归 | 输出不变、退出码不变 | 现有 180 单测 |
| e2e | 现有 11 例 + 真实 PTY `tui` 冒烟 | Testcontainers `xac-e2e:24.04` |

原则：能在视图模型断言的，不在终端断言。

## 9. 交付

- 依赖：`com.googlecode.lanterna:lanterna:3.1.5`（唯一新增）。
- 打包：shade 打进 fat jar，**不得声明任何资源 `filters`/`includes`**（保持通用，不要按资源名做白/黑名单）。Lanterna 3.1.5 的 Linux 终端走 `UnixTerminal`/`stty`，jar 内没有 `terminfo/` 资源（实测 409 个条目，只有 theme/multilang 等 properties），所以「为了 terminfo 而保留资源」这个理由不成立；不过滤是因为过滤本身没有必要且容易误伤。预计 850 KB → 约 1.2 MB。
- 文档：`AGENTS.md`（分层纪律、依赖白名单、`tui` 命令）、`README.md`（TUI 用法与键位）、设计文档新增本章；`xac-yaml-spec.md` 不变。
- 版本：`0.2.0`。
- 平台：Linux/SSH 为主；Windows 不作为 TUI 支持目标。

## 10. 实施顺序

1. **core 抽取**：新增 `ServicePackage` 与返回类型；`ApplyRunner`/`DestroyRunner` 去 `PrintStream`；`StatusResult.format()` 外移；`LogsRunner` 拆为 `LogStream`；`exec` 增 `RunningCommand`。完成后 CLI 行为与输出逐字节不变，180 测试全绿。
2. **TUI 骨架**：`tui` 命令、主屏、状态轮询、日志区、帮助、退出。
3. **动作**：apply 预览与执行、restart、destroy 确认。
4. **编辑**：Schema 表单、校验、`SpecWriter`、`YamlCommentScanner`、diff 与保存。
5. **打包与文档**：shade 验证、真实 PTY 冒烟、文档、版本 `0.2.0`、发布。

每阶段结束都必须 `mvn test` 全绿；第 1 阶段额外要求 `mvn -Pe2e verify` 全绿，证明重构未改行为。

## 11. 已知限制

- 单包视图，不支持多服务切换。
- 编辑不支持原文模式，未知字段无法在 TUI 内修复。
- 注释保留为尽力而为，不保证逐字节还原原文排版。
- `path-links` 目标被真实路径占用时，TUI 只能展示并提示，修复仍需人工处理。
- 不支持 Windows 终端。
