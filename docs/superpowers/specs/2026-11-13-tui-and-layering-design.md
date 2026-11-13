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
- `tui` 不得 import `cli`；展示可直接读 `spec` / `status` 等低层的不可变结果类型（如 `StatusResult`），
  但 systemd 动作编排只能经 `core` / `apply`。
- `cli` 允许 import `tui`，仅限 `RootCommand` 注册 `tui` 子命令这一处（picocli 必需），
  不调用 TUI 的渲染与视图代码。
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

编辑屏：`tab/shift-tab` 切分区，`↑/↓` 选字段，`enter` 编辑或打开子编辑器，`space` 切布尔，`c` 清空标量/枚举，`a` 加行，`x` 删行，`s` 保存，`u` 回滚上一版，`q` 放弃（脏时二次确认）。

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
- 通知行显示成功/失败/“N comment(s) were dropped”，3 秒淡出；错误保留到下次动作。保存成功后主屏 notice 也带上丢失注释的条数（示例行另以 `; M commented-out example` 补充，复数加 `s`）。

### 6.6 动作

- `a`：预览面板列七步；只有 `y` 执行，`enter`/`n`/`esc` 取消；执行期间通知行显示 `running...`、暂停状态轮询，完成后 notice `apply ok` / `apply failed: <msg>` 并立即刷新状态。
- `r`：确认面板列出 `systemctl restart <unit>`；只有 `y` 执行，`enter`/`n`/`esc` 取消；notice `restart ok` / `restart failed: <msg>`。
- `d`：面板列出 willRemove/willKeep；只有 `y` 执行，`enter`/`n`/`esc` 取消（不执行，也不需要输入服务名）；notice `destroy ok` / `destroy failed: <msg>`。
- 动作在独立 daemon 线程执行，UI 不阻塞、每 tick 检查完成标志；执行期间忽略其它按键，但 `q`/`esc`/`ctrl-c` 可放弃等待并退出。放弃等待后进程即将退出：当前命令可能完成，**剩余步骤不会执行**，请重跑 `apply`/`destroy`；不提供 detached 执行。结果不再回写 UI，notice 记 `left running; current step may finish, remaining steps will not; re-run apply/destroy`；退出前不再发起状态查询，避免为一条即将消失的结果再阻塞最多 3 秒。

### 6.7 SpecWriter 契约

`SpecWriter` 是 `ServiceSpec` → `xac.yaml` 文本的唯一出口，供 TUI 保存（§7.3）与 CLI 共用。公开接口只有两个：

```java
String render(ServiceSpec spec)
void save(Path file, String yaml) throws IOException
```

`render` 是纯函数：同样的 spec 必产出逐字节相同的文本。`save` 只负责落盘，不解析、不合并注释；注释由 `YamlCommentScanner` 在 `render` 的输出上合并（§7.4）。

`YamlCommentScanner` 的公开接口：

```java
Comments scan(String yaml)                                  // 扫描既有文件
static Comments scanTemplate()                              // 包内测试用；读 /xac.yaml.template 原文
Comments scanTemplate(String name)                          // 读模板并替换 {{name}}，写盘用
Comments merge(Comments base, Comments override)            // 同路径 override 覆盖 base
ApplyResult apply(String rendered, Comments comments)
ApplyResult apply(String rendered, Comments source, Comments template)  // 模板打底、用户覆盖

final class ApplyResult { String text; int kept; int dropped; int examplesDropped; }
```

`apply` 不重排、不重写任何配置行，只在目标行前插入注释行、在行尾追加同行注释，因此结果仍能被 `SpecLoader` 读回与 `render` 等价的 spec。`kept` 是实际写入的注释条数；无法挂回的用户注释按 §7.4 拆成两个互斥计数：`dropped` 是散文注释，`examplesDropped` 是被注释掉的字段示例（`# workdir: /opt/prod/work` 这类 `key: value`），两者之和供 §6.5 的 “N comment(s) were dropped” 使用。三参重载只统计用户注释，模板里没有对应字段的默认注释不算丢失。用户文件里逐字等于模板原文的注释（`init` 文件首次保存时被空行冲散的模板注释块）也不算丢失，因为模板打底会重新写出它们；比较是逐字相等，用户改动过的模板示例行会被明确计数。模板打底只回写散文注释：`# env:`、`#   GREETING: hello world` 这类列表/字段示例行按 §7.4 只作路径锚点，首次保存后不会重新出现。

注释文本写入前必须同行化转义：`\n` → `\\n`、`\r` → `\\r`、NEL/LS/PS → `\\x85`/`\\u2028`/`\\u2029`，保证程序化构造的 `Comments` 不能用换行注入配置行。

- **段序**：`name` → `description` → `artifact.jar` → `runtime.java` → `runtime.opts` → `env` → `properties` → `workdir` → `path-links` → `logs` → `lifecycle`；段间空一行。
- **排序**：`env` / `properties` 的键按字符串自然序（`TreeMap`）输出，与输入 map 的插入顺序无关，保证同一份配置在任何机器上产生相同 diff；`opts` / `path-links` 是位置敏感的列表，保持原顺序。
- **引号**：能安全用 plain 标量就不加引号；空串、首尾空白、含 `:` / `#` / `${`、形似 YAML 布尔/空值/数字/日期、以 YAML 指示符开头、以 `<<`（merge key 形态）开头、含控制字符时加双引号。引号内转义 `\\`、`"`、`\n`、`\r`、`\t`；不使用 block 标量，保证读回是同一个字符串。
- **转义**：C0（`< 0x20`）、DEL（`0x7f`）、C1（`0x80–0x9f`，含 NEL `U+0085`）写成 `\xNN`；LINE/PARAGRAPH SEPARATOR（`U+2028`/`U+2029`）写成 `\uNNNN`。这些字符若原样输出，loader 会报 `unacceptable code point`，或把 NEL/LS/PS 当换行折叠。
- **换行**：只用 LF，结尾恰好一个 `\n`，无 BOM。
- **空段**：`description`、`env`、`properties`、`workdir`、`path-links`、`logs` 为空时整段省略（与 `xac.yaml.template` 的默认形态一致）；`opts` 为空时显式写 `opts: []`，让「没有 JVM 参数」在文本里可见；`lifecycle` 恒写四键，缺省值取 `Lifecycle` 的文档默认值。
- **路径**：`artifact.jar` 与 `workdir` 相对包目录渲染，分隔符统一为 `/`；`workdir: .` 表示包目录本身，保持为 `.`，不展开成机器相关的绝对路径；与包目录不在同一个根（例如 Windows 换盘符）时退回绝对路径。`logs.file` 是运行时路径，原样输出，不做相对化。
- **软链**：`save` 的目标是软链时，写到 `toRealPath()` 指向的真实文件，链接本身保留；解析失败（悬空链接、无权限）退回普通替换语义。
- **落盘**：父目录必须已存在；同目录建 `.xac-*.tmp`，内容按 UTF-8 写，POSIX 下新建文件设 `0644`，目标已存在时保留其原有权限位（只复制权限，不复制属主，例如既有 `0600` 的 `xac.yaml` 仍是 `0600`）；用 `ATOMIC_MOVE + REPLACE_EXISTING` 覆盖目标。文件系统不支持原子移动时**不回退**到普通 move，而是抛 `IOException`（消息以 `atomic move not supported: <path>` 开头），避免「看似成功、实际非原子」。任何失败都删除临时文件、保留原目标；不备份，也不做 diff/确认。

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

字段顺序固定为 14 个，与 `Schema` 的声明顺序一致：`name`（只读）、`description`、
`artifact.jar`、`runtime.java`、`runtime.opts`、`env`、`properties`、`workdir`、
`path-links`、`logs.file`、`lifecycle.auto-start`、`lifecycle.restart`、
`lifecycle.restart-sec`、`lifecycle.timeout-stop-sec`。行标签用英文，帮助文本取
`Comments` 中同路径的注释：连续前置注释优先（多行以 `\n` 连接），否则用同行注释，
都没有则空。空列表 / 空 map 显示 0 行，不插入占位空行（空白行本身是非法输入）。

### 7.2 校验

- 失焦校验：用草稿构建 `ServiceSpec`，跑 `ValidationRunner`，行尾显示当前字段的 `Violation`；不阻塞编辑。错误归属按**路径前缀**：`runtime.opts[3]`、`env.FOO`、`path-links[0]`、`properties.k` 分别归到 `runtime.opts`、`env`、`path-links`、`properties`，一条字段行只显示该字段的第一条消息。
- 保存前全量校验：有错则停留，底部列错误，光标跳到第一条。进入编辑屏时也先跑一次全量校验并把光标放到第一条错误；保存失败同样重新定位。
- 保存前二次确认：有注释将丢失（`lostComments = dropped + examplesDropped > 0`）或文件被外部改动时，第一次 `s` 只提示，第二次 `s` 才真正写入。三种文案：
  - 仅注释：`! N comment(s) will be dropped; press s again to confirm`
  - 仅外部改动：`! file changed outside; press s again to overwrite`
  - 两者同时：`! N comment(s) will be dropped and the file changed outside; press s again to confirm`

  确认态绑定当时的文件指纹：确认之后文件再变，旧确认作废，必须重新提示；只有两个原因都已在同一份指纹上确认过，才允许落盘，且落盘前仍再验一次指纹。屏幕上 `!` 行已经带总数，`note:` 行只补充“其中多少是示例”，不重复同一个数字。
- `EditorModel.validate()` 是**不抛异常**的全量入口：先检查 `name`、`artifact.jar`、
  `runtime.java` 非空（`Violation(path, "must not be empty")`），再做草稿到 spec 的
  转换，最后跑 `ValidationRunner`；转换失败的 `IllegalArgumentException` 折成
  该字段的 `Violation`，与其他错误一起显示。因此 UI 可以用同一个调用渲染行尾错误。
- 跨字段规则沿用现有 Validator：`${VAR}` 必须在 `env` 存在；`path-links` 目标护栏；绝对路径；换行注入拒绝。

### 7.3 保存

```
s → 草稿 spec → EditorModel.validate（必填非空 + Schema + ValidationRunner）
   ├─ 错误 → 停留、列错、跳第一条
   └─ 通过 → SpecWriter 生成规范文本（含保留注释）
        → 有注释将丢失 / 文件被外部改动 → 记录当前指纹与原因，提示再按一次 s
        → 两个原因都已在该指纹上确认且指纹未再变 → 始终带 baseline 再验一次
          mtime + size + sha256
        → 临时文件（新建 0644，已存在则沿用原权限）+ ATOMIC_MOVE
        → 重新 open → 状态刷新 → 通知 saved（并带上丢失注释条数）
```

安全细节：

1. 进入编辑时记录 `mtime + size + SHA-256`；保存前若变化，提示“文件在编辑期间被外部修改”，
   选择覆盖或放弃。只比 `mtime + size` 会漏掉同长度改写后回填时间戳的情况，因此内容摘要也参与比较；
   确认覆盖时把基线前移到确认时的指纹，下一次 `s` 重新读文件、重新出 diff；确认之后文件再变，
   旧确认作废并重新提示。`commit` 落盘前**始终**再验一次基线（不得传 null 跳过），防止确认与写入
   之间又被改过；复验失败显示 `! cannot save: file changed outside: <path>` 并留在编辑屏。
2. 不自动备份；会话内保留上一版文本供 `u` 回滚。
3. 保存与 apply 解耦，不提供“保存并应用”。
4. 逐行 diff 由 `SaveFlow.Result.diff` 提供；TUI 当前用“二次 `s`”确认代替 diff 面板，面板留待后续迭代。

### 7.4 注释保留

`YamlCommentScanner` 逐行扫描原文，维护缩进路径栈，识别引号外、且前面是空白（或行首）的 `#`，产出 `Comments{inline, leading, orphans}`：

- `path → inline comment`（同行注释）
- `path → List<String> leading`（紧邻上一行）
- `orphans`：文件末尾游离、被空行隔开的注释块、重复列表值的第二份注释（格式 `duplicate comment for <path>: <text>`），带行号前缀

列表项以**值**而不是下标作键（`path-links[./conf:/opt/conf]`），编辑器重排后注释仍跟随原元素；同一个值出现多次时保留第一次出现的元素的注释，其余进入 `orphans` 并计入 `dropped`。重复判定按路径的**出现次数**，不区分同行/前置形态：一条 inline 加一条 leading 挂在同一个重复值上时，第二条同样进 `orphans`。

挂回规则：

| 形态 | 挂回路径 |
|---|---|
| 同行注释 | 标量：字段路径；列表项：按**值**匹配 |
| 前置注释 | 同上，输出为该字段上方若干行 |
| 段头注释 | 段路径（如 `env`） |

`SpecWriter` 按固定顺序输出（与模板一致）。模板打底由 `scanTemplate(String name)` 完成：它解析随包分发的 `xac.yaml.template`，把注释里的 `{{name}}` 换成真实服务名（与 `init` 的替换口径一致），被注释掉的示例段（`# env:`、`#   workdir: /opt/x`）只作为路径锚点，示例行本身不产出注释；`apply(rendered, source, template)` 先 `merge(template, source)`（用户文件同路径覆盖模板）再挂回。这样 init 生成的包首次保存后模板说明仍在、不会出现字面量 `{{name}}`，用户改过的字段用用户注释；模板里没有对应字段的默认注释不计入 `dropped`，用户文件里逐字等于模板原文（含被空行冲散的模板块）也不计入。`env`/`properties` 的键按字符串自然序输出（与插入顺序无关），`opts`/`path-links` 保持原顺序；结尾单个 `\n`，LF。完整契约（含引号、转义、路径与落盘语义）见 §6.7。

块标量（`|`/`>`）内部的 `#` 是标量内容，既不是注释，也不计入 `dropped`。`apply` 只增注释行，不重排配置行；对 `scan` 结果 apply 是幂等的，对已合并文本再次 apply 也不会重复插入。

无法定位的用户注释由 `ApplyResult` 拆成两个互斥计数：`dropped` 是散文注释，`examplesDropped`
是被注释掉的字段示例（`# workdir: /opt/prod/work` 这类 `key: value` 形态）；与模板逐字相同的
orphan 两栏都不计。示例识别只认 DSL 键名（`name`/`description`/`artifact`/`runtime`/`env`/
`properties`/`workdir`/`path-links`/`logs`/`lifecycle` 及其子键），`# Note: this is prose` 这类
自由文本算散文。总数 `lost = dropped + examplesDropped`，提示语为 `N comment(s) will be dropped`，
`note:` 行只在有示例时补充 `M commented-out example`（复数加 `s`），不重复总数。保存成功后主屏
notice 用 `N comment(s) were dropped`（示例同样以 `; M commented-out example` 补充）。
保存前必须显示条数并要求二次确认（第一次 `s` 不写），避免这类用户手写内容无声消失；diff 面板留待后续迭代。

### 7.5 限制

- 语法错误或含未知字段的 `xac.yaml` 无法构建模型 → 不能进入编辑器，需手改。
- 注释保留是尽力而为：只保留紧邻的一段。
- 编辑不做环境预检（jar 是否存在、是否 root、是否 systemd 机器）。

## 8. 测试

| 层 | 内容 | 手段 |
|---|---|---|
| core | `ServicePackage` 七类返回与副作用 | `RecordingRunner` + 临时目录 |
| 视图模型 | 状态行、漂移着色、编辑字段树、校验定位、diff 文本 | 纯 JUnit，不碰终端 |
| TUI 壳 | 键位映射、覆盖层开关、resize 不崩 | 注入 `Display` / `KeySource`；Lanterna 适配层用动态代理 + `RecordingScreen`、匿名 `InputProvider` |
| CLI 回归 | 输出不变、退出码不变 | 现有 504 单测 |
| e2e | 现有 11 例（含 `tui` 非 TTY 退 2；无真实 PTY） | Testcontainers `xac-e2e:24.04` |

原则：能在视图模型断言的，不在终端断言。

## 9. 交付

- 依赖：`com.googlecode.lanterna:lanterna:3.1.5`（唯一新增）。
- 打包：shade 打进 fat jar，**不得声明任何资源 `filters`/`includes`**（保持通用，不要按资源名做白/黑名单）。Lanterna 3.1.5 的 Linux 终端走 `UnixTerminal`/`stty`，jar 内没有 `terminfo/` 资源（实测 409 个条目，只有 theme/multilang 等 properties），所以「为了 terminfo 而保留资源」这个理由不成立；不过滤是因为过滤本身没有必要且容易误伤。预计 850 KB → 约 1.2 MB。正因为 3.1.5 没有 `terminfo/`，计划里验证资源打包的 `tuiLoadsLanternaTerminfo` 冒烟作废，不再添加。
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

## 12. 实现落点

本章记录设计各节最终落在哪些类上，以及实现与设计稿的已知偏差。

### 12.1 分层与 core 门面（§4、§5）

| 设计落点 | 实际类 |
|---|---|
| 唯一门面 | `io.xac.systemd.core.ServicePackage`（`open` / `spec` / `isRoot` / `status` / `plan` / `logs` / `apply` / `restart` / `destroyPlan` / `destroy`） |
| 状态聚合 | `io.xac.systemd.core.StatusReport`（`StatusResult` + 漂移/查询结果） |
| 日志接缝 | `io.xac.systemd.logs.LogStream`，实现 `JournaldLogStream`、`FileLogStream`（文件跟随用 `FileTail`） |
| 入口 | `cli` 各命令与 `tui/TuiCommand` 都经 `ServicePackage`；`tui` 不 import `cli`，`cli` 只在 `RootCommand` 注册 `tui` 子命令处 import `tui`（picocli 必需，唯一例外） |

### 12.2 TUI（§6）

| 设计落点 | 实际类 |
|---|---|
| 主循环、轮询、动作 | `io.xac.systemd.tui.TuiApp` |
| 终端接缝 | `tui/Display` + `tui/LanternaDisplay`；`tui/KeySource` + `tui/LanternaKeySource`（`NavKey` / `KeyEvent` 为平台无关键位） |
| 状态区 | `tui/StatusView` + `tui/TuiState` |
| 日志区 | `tui/LogPane`（滚动、follow、容量上限） |
| 动作确认 | `tui/actions/ActionOverlay` + `tui/actions/ActionKind` |
| 命令与非 TTY 判定 | `tui/TuiCommand`（`isInteractiveTerminal`） |

### 12.3 编辑与保存（§7）

| 设计落点 | 实际类 |
|---|---|
| 字段树与校验 | `tui/edit/EditorModel` + `EditorField`（不可变模型，`with` 返回副本） |
| 编辑屏 | `tui/edit/EditScreen`（浏览 / 行列表 / 标量三种模式） |
| 保存流程 | `tui/edit/SaveFlow`（校验 → 渲染 → 注释合并 → 二次确认 → 原子写） |
| 文件指纹 | `tui/edit/FileStamp`（保存前发现外部改动） |
| yaml 文本 | `spec/SpecWriter`、`spec/YamlCommentScanner`、`spec/Comments`、`spec/LineDiff` |

### 12.4 与设计稿的偏差

- **diff 面板未渲染**：`LineDiff` 已在 `SaveFlow` 内用于判断保存会不会丢注释，但编辑屏没有逐行 diff 预览；本轮只靠「按两次 `s`」的确认文案（§7.3 描述的 diff 面板留作后续）。
- **失焦校验实现为全量刷新**：`EditorModel.validate()` 在每次 `with(...)` 变更后整份重跑，而不是只校验失焦字段；效果等价，代价是每次按键多一次全量校验（字段数量小，未做增量优化）。
- **TUI 动作不提供 detached 执行**：`apply` / `restart` / `destroy` 都在 TUI 进程的 daemon 动作线程里执行；执行中退出会放弃等待，只有当前外部命令可能自行跑完，剩余步骤不再执行（notice `left running; current step may finish, remaining steps will not; re-run apply/destroy`）。没有「脱离终端继续跑」的模式。
- **帮助面板未实现**：`?` 只设置 notice `help is not implemented yet`，没有覆盖式帮助画面；键位仍显示在底栏和本文档中。
- **没有真实 PTY 冒烟**：§8 测试表与 §10 第 5 项提到的真实 PTY `tui` 冒烟没有落地；e2e 只有 `SmokeIT#tuiRefusesToStartWithoutATty`，验证非 TTY 时退 2。TUI 行为由 `Display` / `KeySource` 注入的单元测试覆盖。
- **测试数字**：§8 与 §10 中的「180 单测」「11 例 e2e」是写作时的历史值；0.2.0 实际为 504 个单测（`mvn test`）与 11 个 e2e（`mvn -Pe2e verify`，其中 1 个是 `tui` 非 TTY 退 2）。
