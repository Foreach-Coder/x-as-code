# java-systemd-service-as-code 设计（v1）

> 状态：待评审。
> 首版范围：`apply`（含 `--dry-run`）/ `status` / `logs` / `destroy`。
> DSL 规范：`java-systemd-service-as-code/docs/xac-yaml-spec.md`（本次新写）。
> 旧的 `docs/service-yaml-spec.md` 保留为原始参考，不再维护。

## 0. 本稿相对上一稿的改动

1. DSL 文件名 `service.yaml` → **`xac.yaml`**；规范新写到 `xac-yaml-spec.md`
2. `links` → **`path-links`**
3. 启停段仍叫 **`lifecycle`**（不叫 `systemd`）：它是工具的抽象层，不是 systemd 指令直通表。
   键为 `auto-start`（布尔）/ `restart`（枚举）/ `restart-sec` / `timeout-stop-sec`，
   取消原 `enabled`、`stop-timeout`，也不提供 `wanted-by`
4. 删掉堆大小校验与 GC/堆转储目录创建：**不再解析 `opts` 的内容**
5. `${...}` **不在渲染期展开**，原样写进 unit，由 systemd 在服务启动时用环境变量展开
6. **`destroy` 纳入首版**（删除前 `y/N` 确认）
7. 动作执行顺序由程序固定，**与 yaml 字段书写顺序无关**
8. **所有命令都接收"包目录"**，包目录下必须有 `xac.yaml`
9. unit 恒定渲染 **`SuccessExitStatus=143`**（否则停服务后 unit 被标成 `failed`）
10. 目标平台定案 **Ubuntu 24.04 / systemd 255**，移出 CentOS 7 兼容
11. `--dry-run` 先过预检再打印（§6.2）：预检不通过按输入错误退 2
12. `destroy` 的 `stop`/`disable`/`reload` 失败只记警告（§6.5）：destroy 的目标是文件消失
13. 交付形态改为 **`tar.gz` 发行包**（`bin/` 启动脚本 + `libs/` fat jar），release 只发这个包（§8）
14. 启动脚本自带 **`install` / `uninstall`**：把自己软链进 `/usr/local/bin`，只有脚本知道自己的真实路径（§8）

## 1. 背景与目标

`xac`（x-as-code）是用代码定义和管理一切的项目族。首个交付物 `java-systemd-service-as-code`：
用一个包目录 + 一份 `xac.yaml` 描述一个跑在 systemd 上的 Java 服务，用
`java-systemd-service-cli` 管理它的全生命周期。

### 1.1 非目标（v1）

- 不做远程编排（CLI 只在目标机本机执行）
- 不做产物上传/分发（jar、配置、依赖由 CI 或人工就位）
- 不做多服务/项目层编排
- 不做 `diff` / `render` 命令（渲染器内部仍需实现）
- 不做安装状态比对后的条件执行（`apply` 每次全量重建并重启）
- 不做并发保护
- 不做 systemd 加固、`User=` 切换、日志文件落地
- 不支持 CentOS 7 及更早的 systemd（219 及以下）

## 2. 关键决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 只在目标机本机执行，无 SSH / 无 remote 层 | 运维把包和 CLI 放到机器上直接跑 |
| D2 | 只做控制面，不上传产物 | 无大文件传输问题 |
| D3 | 单服务，一个包目录一个服务 | 不做 compose 式编排 |
| D4 | Java，JDK 21 编译、`--release 8`；已验证在 JDK 21 运行时上运行 | fat jar 零安装；保留 8 只为兼容老机器，成本仅是不用新语法 |
| D5 | 单 yaml 文件，不引入第二份配置、不做多环境 overlay | 一个环境一份 yaml |
| D6 | 声明式 `apply` 为核心 | 重跑安全，CI 友好 |
| D7 | 部署侧参数全部内置默认值，不开放配置 | v1 最小 |
| D8 | 无状态：不生成 state 文件 | 一切依据来自本机现状 |
| D9 | 严格模式：未知字段报错 | 拼错键名的代价大于前向兼容成本 |
| D10 | `apply` 不做 diff，无条件全量重建 + 重启 | 简单、行为可预测 |
| D11 | 不处理并发 | 单机人工执行，冲突概率可忽略 |
| D12 | **不解析 `opts` 的内容**（不查堆大小、不推导目录） | 猜参数含义不可靠，也不是工具的职责 |
| D13 | **变量引用由 systemd 在启动时展开**，渲染期不插值 | 密码只落在 0600 的 env 文件里；与 systemd 语义一致 |
| D14 | **`destroy` 纳入首版** | yaml 里声明的东西要能被完整撤销 |
| D15 | **启停参数集中在 `lifecycle` 段，作为工具的抽象** | systemd 指令有几百条，全量透传不可行；键名尽量贴合 systemd，但按语义封装（`auto-start` 比 `wanted-by` 更贴近使用意图） |
| D16 | **命令以包目录为参数**，包目录下必须有 `xac.yaml` | 一个包目录就是一次部署单元；避免"yaml 在别处、产物在别处" |
| D17 | **目标平台 Ubuntu 24.04（systemd 255）**，不兼容 CentOS 7 / systemd 219 | 用户定案：新系统统一 Ubuntu 24.04；不为老发行版的 systemd 差异做兼容分支 |
| D18 | **unit 恒定渲染 `SuccessExitStatus=143`** | JVM 处理 SIGTERM 后以 143 退出，systemd 视为失败；不写则 `systemctl stop` 把 unit 标成 `failed`，会被 `status` 误报（实测） |

### 2.1 Java 8 目标的技术约束

- 必须 `--release 8`（不用 `-source/-target 8`）
- 禁用 `record` / `var` / `switch` 表达式 / 文本块 / `List.of` / `Map.of` / `Optional.isEmpty()`
- 依赖锁在支持 Java 8 的版本：picocli 4.x、SnakeYAML 2.x、JUnit 5.x
  （不需要 Jackson：yaml 用 SnakeYAML，v1 无 JSON 输出）

### 2.2 两个文档的分工

| 文档 | 位置 | 管什么 |
|---|---|---|
| DSL 规范 | `java-systemd-service-as-code/docs/xac-yaml-spec.md` | 用户写 `xac.yaml` 时看什么 |
| 设计文档（本文） | `docs/superpowers/specs/2026-11-10-…-design.md` | 实现怎么做 |

`docs/service-yaml-spec.md` 是最初的参考稿，保留但不再更新。

## 3. 架构

```
        ┌──────────────── cli (picocli) ────────────────┐
        │ apply  status  logs  destroy   ← 都收包目录     │
        └───────────────────────┬───────────────────────┘
                                │
        ┌───────────────────────▼───────────────────────┐
        │ apply  ApplyRunner / DestroyRunner            │ 固定顺序的 Action 列表
        │        (List<Action>)                         │ --dry-run 只打印不执行
        └───┬───────────────────────────────────────┬───┘
            │                                       │
    ┌───────▼────────┐                      ┌───────▼──────────────┐
    │ systemd        │                      │ exec                 │
    │  UnitRenderer  │                      │  CommandRunner(接口) │
    │  UnitNaming    │                      │  ProcessRunner(真)   │
    │  SystemdTarget │                      │  RecordingRunner(测) │
    └───────┬────────┘                      └──────────────────────┘
            │
    ┌───────▼────────┐
    │ spec           │  SnakeYAML → schema 校验 → 模型
    └────────────────┘
```

依赖方向只能向下。纪律：`exec` 包里不出现 systemd 字样；`spec` 包里不出现进程执行。
两者是将来抽 `xac-core` 的接缝。

### 3.1 包结构

```
java-systemd-service-as-code/
  pom.xml
  docs/xac-yaml-spec.md
  docs/service-yaml-spec.md        （原始参考，不再维护）
  src/main/java/io/xac/systemd/
    cli/      ApplyCommand、StatusCommand、LogsCommand、DestroyCommand、根命令
    spec/     ServiceSpec 及子模型、Schema、SpecLoader、Violation、Validator 实现
    systemd/  UnitRenderer、UnitNaming、SystemdTarget、Directive 实现
    exec/     CommandRunner、ProcessRunner、RecordingRunner、ExecResult
    apply/    ApplyRunner、DestroyRunner、Action 实现、ApplyContext
  src/test/java/io/xac/systemd/...
```

### 3.2 核心抽象

```java
// exec —— 全仓库唯一允许调用外部进程的地方
public interface CommandRunner {
    ExecResult run(List<String> argv);        // 不经过 shell，参数逐项传
    void runInheritIo(List<String> argv);     // logs -f 用，直通终端
}

public final class ExecResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public boolean ok() { return exitCode == 0; }
}

// apply / destroy —— 固定顺序的动作，没有「要不要做」的判断
public interface Action {
    String describe();                                  // 打印用，如 "write unit file"
    void run(ApplyContext ctx) throws ActionFailure;
}

// spec —— 一条规则一个类
public interface Validator { void validate(ServiceSpec spec, List<Violation> out); }

// systemd —— 纯函数，确定性
public interface UnitRenderer { String render(ServiceSpec spec); }
```

`ApplyContext` 持有：已解析的 `ServiceSpec`、包目录、渲染好的 unit 内容、
`CommandRunner`、unit 目录与 env 目录、`dryRun` 标志。

## 4. 规范扩展空间

扩展动作必须永远是「追加」，不改主干。

1. **解析层：自建 schema 表**。`FieldDef` 声明每个 section 允许的键、类型、转换器。
   报错能给出 `runtime.opts[3] must be a string` 这种精确路径。加字段 = 表里加一行。
2. **模型层：字段全可空 + Builder**。必填性归 Validator，构造函数不约束。
3. **校验层：`List<Validator>`**。规范 §6 的每条规则一个类，加规则 = 加类 + 注册一行。
4. **渲染层：`List<Directive>`**。每条 unit 指令一个类，返回 `Optional<String>`。
   加指令 = 加类。顺序显式写死以保证确定性。

顶层用分节（`artifact` / `runtime` / `env` / `properties` / `workdir` / `path-links` / `lifecycle`），
将来加 `hardening` / `args` 等都是新增 section，与现有字段零交集。

`env` 与 `properties` 是开放 map，键任意——大部分需求不必动 DSL。

严格模式不放松：未知字段报错。真需要兼容时再加顶层 `version: 1`（缺省视为 v1）。

## 5. unit 渲染

### 5.1 产物

| 文件 | 路径 | 权限 |
|---|---|---|
| unit | `/etc/systemd/system/xac.<name>.service` | `0644 root:root` |
| env  | `/etc/xac/xac.<name>.env` | `0600 root:root` |

- 生成名统一加 `xac.` 前缀，与系统 unit / env 文件分属不同命名空间；yaml 里的 `name` 仍是服务名（不带前缀）
- unit 放 `/etc/systemd/system`（systemd 规定管理员自建 unit 的位置）
- env 放 `/etc/xac/`（**发行版无关**：`/etc/sysconfig` 是 RHEL 惯例、Ubuntu 上不存在；`/etc/default` 是 Debian 惯例。这个文件的唯一读者是 `EnvironmentFile=`，放在自己的命名空间里不会和系统包撞名）
- 头部各一行 `# Managed by xac. Do not edit.`，**不带版本号与摘要**（避免假 diff）
- unit 目录与 env 目录均可注入，测试用临时目录

### 5.2 指令来源

| 指令 | 来源 | 备注 |
|---|---|---|
| `Description=` | `description` | 空则不写 |
| `WorkingDirectory=` | `workdir` | 缺省 = 包目录，绝对路径 |
| `EnvironmentFile=` | `env` | env 为空则整行不写、也不生成 env 文件 |
| `SyslogIdentifier=` | `name` | 值为 `xac.<name>`（前缀 + name，不含 `.service`） |
| `ExecStart=` | 见 5.3 | |
| `Restart=` | `lifecycle.restart` | 原样透传 systemd 取值 |
| `RestartSec=` | `lifecycle.restart-sec` | 缺省 5 |
| `TimeoutStopSec=` | `lifecycle.timeout-stop-sec` | 缺省 60 |
| `SuccessExitStatus=143` | 内置常量 | JVM 收到 SIGTERM 通常以 143 退出；不写则 `systemctl stop` 后 unit 变 `failed`（实测） |
| `[Install] WantedBy=` | `lifecycle.auto-start` | `true` → `multi-user.target`；`false` → **整段 `[Install]` 不写** |
| `Type=simple` | 内置常量 | |

`auto-start: false` 时不写 `[Install]` 段，apply 里对应执行 `systemctl disable`。**实测**：没有
`[Install]` 的 unit，`systemctl enable` 只打印一段说明并**退出 0**（`UnitFileState=static`），不会
失败；反过来，从一个曾经 enabled 的 unit 上删掉 `[Install]` 再 `daemon-reload` + `disable`，旧的
`multi-user.target.wants/` 链接会被正确清掉。自启状态只由 `[Install]` 段决定，与 systemd 自身语义一致。

### 5.3 ExecStart 拼装

顺序固定：`<java> <opts 原样，按 yaml 顺序> <-Dk=v，按 properties yaml 顺序> -jar <jar 绝对路径>`。

properties 放 opts 之后，使 `-D` 可覆盖 opts 中的同名定义。v1 不支持应用参数。

只做两件事，**不做插值**：

1. `%` → `%%`（unit 文件里 `%` 是 specifier，systemd 自己会做展开）
2. 参数含空白 → 整个参数用双引号包起来

`$` 完全不动：`${VAR}` 原样写进 unit，交给 systemd 在启动时展开；字面的 `$` 由 DSL 约定写成 `$$`。

例（规范 §7.2 的样例）渲染出的 ExecStart 片段：

```
ExecStart=/usr/local/jdk8/bin/java -Xmx2457m -XX:MaxMetaspaceSize=512m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/applog/messystemservice/heapdump \
  -Xloggc:/applog/messystemservice/gc/gc.log -XX:+UseG1GC \
  -Dspring.profiles.active=${SPRING_PROFILES} -Dapplication.appId=${APP_ID} \
  -Dconfigcenter.password=${CONFIGCENTER_PWD} \
  -jar /…/libs/mesunisystemservice-nonhis-basic-3.6.6.0.jar
```

unit 里只有变量名，密码在 env 文件里。

### 5.4 为什么不自己展开变量

1. **密码不外泄**：unit 是 0644、`systemctl show` 人人可看；env 文件是 0600 root。渲染期展开会把
   密码从后者搬到前者。
2. **语义本来就归 systemd**：`$VAR` / `${VAR}` 的展开是 systemd 的既有能力，工具再实现一遍等于
   定义两套略有差异的语法。
3. **少一层逻辑**：渲染器退化成纯字符串拼接。

代价：`${名字}` 引用不到时，systemd 会静默展开成空串。所以校验阶段要求每个引用都能在 `env` 里
找到——那是静态检查，不是展开。

> 已在 Ubuntu 24.04 / systemd 255 实测：`EnvironmentFile` + `ExecStart` 里的 `${VAR}` 按预期展开，
> 值含空格时仍是单个 argv；未定义变量静默变空串。

### 5.5 env 用 `EnvironmentFile=` 的理由

1. **`%` 零转义**：EnvironmentFile 的内容不参与 specifier 展开
2. **密码不泄漏**：`Environment=` 的内容任何用户 `systemctl show` 可见；EnvironmentFile 是 0600 root
3. unit 文件稳定，env 变化不触发 unit 重写

### 5.6 显式不写

| 不写 | 后果 | 原因 |
|---|---|---|
| `User=` / `Group=` | 进程以 root 运行 | systemd 默认；猜用户名比不猜更危险 |
| 加固项 | 无 | 加固策略应由运维按机器定 |
| `StartLimitBurst` / `StartLimitIntervalSec` | systemd 默认 10 次/10 秒 | 配合 `RestartSec=5` 不会误杀也不会热循环 |
| `After=network-online.target` | 可能早于网络就绪 | 会让开机变慢，规范未要求 |

### 5.7 确定性

`render` 必须是纯函数：不写时间戳/主机名/随机数，map 按 yaml 原始顺序（SnakeYAML 给
LinkedHashMap），输出以单个 `\n` 结尾。

### 5.8 写入方式

写同目录临时文件 → `Files.setPosixFilePermissions` → `ATOMIC_MOVE` 重命名。
避免写到一半留下残缺 unit。

## 6. 命令行为

### 6.0 调用形态

四个命令的**第一个位置参数都是包目录**，缺省为当前目录 `.`。包目录下必须有 `xac.yaml`，
否则退出码 2。

```
xac apply     [<package-dir>] [--dry-run]
xac status    [<package-dir>]
xac logs      [<package-dir>] [-n <行数>] [-f]
xac destroy   [<package-dir>] [--dry-run] [--yes]
xac install   [--dry-run]      # 由发行包的启动脚本处理，见 §8
xac uninstall [--dry-run]      # 由发行包的启动脚本处理，见 §8
```

包目录会被绝对化（`toAbsolutePath().normalize()`），之后所有相对路径（`artifact.jar`、`workdir`、
`path-links` 的源、缺省 `workdir`）都相对它解析。

`<name>` 一律取自 yaml 的 `name` 字段，与包目录名无关。

### 6.1 校验清单（动手前）

任一条失败 → 退出码 2，一个文件都不写。

**输入检查**

1. 包目录存在且是个目录
2. 包目录下有 `xac.yaml`
3. 当前用户是 root（`id -u` == 0）——`status`、`logs` 不要求
4. `/run/systemd/system` 存在（systemd 在运行）——同上

**静态校验**（只看 yaml 文本，与规范 §6 一一对应）

5. 顶层是映射；`name`、`artifact.jar`、`runtime.java`、`runtime.opts` 齐备
6. 无未知字段、无类型不符（报精确路径，如 `runtime.opts[3] must be a string`）
7. `name` 只含字母数字 `-` `_` `.` `:`，不以 `.` 开头，不含 `/`，且不以 `xac.` 开头（不区分大小写；工具会自动加该前缀）
8. `env` 键名合法、值不含换行、键不重复
9. `path-links` 每项按最后一个冒号切成两段非空，目标以 `/` 开头
10. `path-links` 的目标不得落在受保护位置：`/` 本身，`/etc`、`/usr`、`/bin`、`/sbin`、`/lib`、
    `/lib32`、`/lib64`、`/boot`、`/var`、`/root`、`/home`、`/proc`、`/sys`、`/dev`、`/run` 及其子孙；
    也不得等于或包住包目录、unit 目录、env 文件
11. `path-links` 的目标不得等于源，也不得是源的上级目录（后者会建出自指的死链）
12. `properties` 与 `runtime.opts` 里的 `${...}` 都能在 `env` 中找到
13. `lifecycle.auto-start` 是布尔；`lifecycle.restart` 是合法取值；两个秒数是正整数
14. 会进入 unit 的字符串不含换行：`description`、`artifact.jar`、`runtime.java`、`workdir`、
    `runtime.opts` 的每一项、`properties` 的键与值

第 10、11 条按**两种形式**各判一次：yaml 里的字面路径，以及把已存在的父目录解析成真实路径之后的
路径（`/opt/link -> /` 这种预先布置的链接不能用来绕过）。末段不解析——删一个链接时删的就是链接本身。

**环境检查**

15. `artifact.jar` 存在
16. `runtime.java` 存在且可执行
17. `path-links` 的源存在
18. `path-links` 的目标要么不存在，要么已是指向源的软链接；其余形态一律拒绝，交给人处理
19. 同名 unit 不是由 systemd 提供的：`systemctl show xac.<name>.service --property=LoadState,FragmentPath`
    查一次；查询本身失败直接拒绝；`FragmentPath` 非空且不等于本次要写的 unit 路径（两边都
    `toAbsolutePath().normalize()` 后比较）即视为同名 unit 已存在，请人处理；`LoadState=not-found`
    或 `FragmentPath` 为空则放行
20. 已存在的 unit 文件（`<unit 目录>/xac.<name>.service`）必须是普通文件且首行是 xac 管理头，
    否则拒绝覆盖
21. 已存在的 env 文件（`<env 目录>/xac.<name>.env`）必须是普通文件且首行是 xac 管理头，
    否则拒绝覆盖；`env` 为空时的删除同样受这条约束

第 19-21 条是归属与影子检查，**只在 apply（含 `--dry-run`）检查**，且发生在任何写入之前。
`destroy` 另有自己的一套：只对普通文件且带管理头的 unit 执行 `stop`/`disable` 并删除。

第 17、18 条**只在 apply 检查**。`status`、`logs`、`destroy` 要能在包目录内容已经没了的时候继续工作
（典型场景：先把产物删了，再拆服务）。

### 6.2 apply

```
xac apply [<package-dir>] [--dry-run]
```

**无差别执行**：不看现状、不做 diff、不跳过任何步骤。每次 apply 都重写文件并重启服务。

固定动作序列（**顺序由程序决定，与 yaml 里字段的书写顺序无关**）：

| # | 动作 | 落到机器上的操作 |
|---|---|---|
| 1 | `path-links` | 软链接（目标不存在则建；已指向正确源则跳过；其余形态一律拒绝，整次 apply 退 2） |
| 2 | `workdir` | 目录不存在时 `createDirectory`（只建最后一级，父目录不存在报错） |
| 3 | `env` | 写 `/etc/xac/xac.<name>.env`（目录不存在则建），0600；`env` 为空则删掉遗留文件 |
| 4 | `unit` | 写 `/etc/systemd/system/xac.<name>.service`，0644 |
| 5 | `reload` | `systemctl daemon-reload` |
| 6 | `auto-start` | `lifecycle.auto-start: true` → `systemctl enable xac.<name>.service`；`false` → `systemctl disable xac.<name>.service` |
| 7 | `restart` | `systemctl restart xac.<name>.service`（未运行时等价于 start） |

`path-links` 排在 `workdir` 之前是有意的：样例里 `workdir` 与某个 link 的目标可能是同一个路径
（`workdir: /appfile/messystemservice` 与 `./appfile:/appfile/messystemservice`）。链接先建好，
`workdir` 那步看到路径已存在就跳过。目标处若是别的东西，`path-links` 直接退 2，`workdir` 根本不会执行。

`--dry-run`：**先过 §6.1 的预检**，通过后打印 7 个动作的描述并退出 0，不写文件、不执行命令；
预检失败按 §6.1 退 2（什么都没改动）。dry-run 的价值是「这些动作在这台机器上真能执行」，
把预检跳过去，打印出来的就只是一段文本。非 root 机器上的纯预览留到后续版本。

**软链接是唯一保留状态分支的地方**（这不是 diff，是正确的覆盖语义）：

| 目标现状 | 动作 |
|---|---|
| 不存在 | 建链接 |
| 已是指向正确源的软链接 | 跳过 |
| 真实目录（空或非空）、普通文件、指向别处的软链接、悬空软链接、FIFO/socket 等 | **拒绝**：整次 apply 退 2，不移动、不删除、不覆盖任何东西，交给人处理 |

拒绝时的报错是 `target exists and is not a symlink to source, handle it manually: <目标>`。实现上彻底移除了
`<目标>.bak` 备份与 `Files.move` 用户数据的时间戳逻辑——xac 不再替用户决定旧目录/旧文件的去留。

**护栏先于动作**：目标落在受保护位置（§6.1 第 10 条）、与源互相包含（第 11 条），或目标已存在
却不是指向源的软链接（第 18 条）时，apply 在写任何文件之前就退 2。护栏存在的理由很具体——
`./conf:/etc` 这类笔误会让系统目录成为链接目标，`./u:/etc/systemd/system` 会端掉 unit 目录；
护栏执行前先解析真实路径，避免用链接绕过。

拒绝发生在改动之前，原路径原样保留；人工处理掉障碍后重新 apply 即可。

**失败语义：不自动回滚。** 停在失败的动作，输出 `failed: ...` 诊断，退出码 1。已完成的部分保留——
下次 apply 会重新覆盖一遍。

**已知代价**：每次 apply 都会重启服务，即产生短暂停机；CI 每次触发也会重启。
需要「只在变化时重启」时，后续可加 `--no-restart`。

### 6.3 status

```
xac status [<package-dir>]
```

只读，不需要 root。三次外部读取 + 若干次文件读取：

```
systemctl show xac.<name>.service \
  --property=LoadState,ActiveState,SubState,UnitFileState,MainPID,ActiveEnterTimestamp,FragmentPath
```

（这些属性 systemd 255 均支持；**不用 `MemoryCurrent`**，status 不显示内存。）

示例输出：

```
service:     messystemservice
package:     /appfile/messystemservice
unit:        /etc/systemd/system/xac.messystemservice.service   [installed, in sync]
env:         /etc/xac/xac.messystemservice.env                  [installed, in sync]
auto-start:  enabled      (yaml auto-start: true)
active:      active (running) since 2026-11-09 21:03:11, PID 12345
path-links:  ok (2/2)
drift:       none
```

`in sync` 的比较方式：读文件内容与当前渲染结果逐字节比较。有差异时 `drift:` 行给摘要并提示
运行 `xac apply`。

**退出码**：`0` = 已安装、已启用、正在运行、无漂移；`1` = 存在偏差（可直接用于 CI 巡检）。

**「正在运行」怎么判**：读 `ActiveState`/`SubState`。不是 `active` 就记一条漂移（`systemctl stop`
之后必须能看出来，否则 status 会对着一个停掉的服务报绿灯）。`systemctl show` 本身失败时记
`systemd: cannot query xac.<name>.service`；查询成功但没有 `ActiveState` 字段时不判漂移——观测不到
不等于观测到「没在跑」。

**env 不可读不算漂移**：env 文件是 0600 root，非 root 跑 status 读不到内容。这种情况打
`env: (cannot verify: root required)`，不记漂移，退出码只看其余项——否则健康机器上的非 root 巡检
永远是红的。文件不存在（`MISSING`）仍然算漂移。

### 6.4 logs

```
xac logs [<package-dir>] [-n <行数>] [-f]
```

```
journalctl -u xac.<name>.service --no-pager -n <N>
journalctl -u xac.<name>.service --no-pager -n <N> --follow    # -f 时，走 runInheritIo 直通终端
```

- 必须带 `--no-pager`，否则会进 less 卡住
- 参数以 argv 逐项传，不经过 shell
- 不需要 root（能否看系统日志取决于用户是否在 `systemd-journal` 组）

### 6.5 destroy

```
xac destroy [<package-dir>] [--dry-run] [--yes]
```

需要 root。目标：让这个服务在本机上不存在。默认先打印 `will remove:` 清单与 `keep:` 项，并要求输入 `y` 确认；
`--yes` 跳过（CI 用）；非交互环境又没给 `--yes` 时退出码 2。

**先判归属，再动手**：只有当 `/etc/systemd/system/xac.<name>.service` 是普通文件（lstat，不跟随链接）
且首行是管理头时，才执行 `stop`/`disable`/`rm-unit`。其余情形一律跳过这三个动作并输出 `warning: ...`，然后继续
删 env、reload、删链接：

| unit 路径现状 | stop/disable/rm-unit |
|---|---|
| 管理头 + 普通文件 | 执行 |
| 普通文件但没有管理头（别人的 unit） | 跳过 + 警告 |
| 不存在（vendor unit 在 `/usr/lib/systemd/system`） | 跳过 + 警告：`unit with same name is not managed by xac, not stopped; stop it manually if a process is still running` |
| 目录、dangling 链接等非普通文件 | 跳过 + 警告 |

这条闸门的代价要说清楚：如果 unit 文件被手工删掉了而进程还在，destroy 会退 0 但不杀进程——警告
会写出来，但不会替你收拾。反过来，同名 vendor unit（如 `ssh`）永远不会被误停。

env 文件用同一把尺子：只有 `/etc/xac/xac.<name>.env` 是普通文件且首行是 xac 管理头时才删除，
其余情形跳过并警告，绝不覆盖或删除别人的文件。

确认提示列出**将要删除**的项和**确认保留**的项（包目录、workdir、日志）。

**不要求 `artifact.jar` 与 `runtime.java` 存在**：产物先删、后拆服务是正常顺序。destroy 的预检只有
root 与 `/run/systemd/system`。

| # | 动作 | 落到机器上的操作 |
|---|---|---|
| 1 | `stop` | `systemctl stop xac.<name>.service`；失败只记警告，不中止 |
| 2 | `disable` | `systemctl disable xac.<name>.service`；失败只记警告 |
| 3 | `rm-unit` | 删 `/etc/systemd/system/xac.<name>.service`（不存在则跳过） |
| 4 | `rm-env` | 删 `/etc/xac/xac.<name>.env`（不存在则跳过） |
| 5 | `reload` | `systemctl daemon-reload`；失败只记警告，不中止 |
| 6 | `rm-links` | 删 `path-links` 里的链接，**仅当它是软链接且指向配置的源** |

为什么 stop/disable 失败不中止：destroy 的语义是「让这些东西不存在」，本来就不存在时 systemctl
会返回非 0（实测：不存在的 unit `stop` 退出 5、`disable` 退出 1）；那不是错误。文件层面的删除
才是目标。`reload` 同理：reload 只刷新 systemd 的内存态，删文件失败才是真失败；这台机器
systemd 起不来时（`degraded`），reload 失败也挡不住「文件已经清掉」这个结果，下一次 `apply`
必然会再 reload 一次。

**不删**：包目录、workdir、已产出的日志。

**幂等**：什么都没装的时候跑 destroy 也返回 0。

### 6.6 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 执行期失败（某动作失败、status 检出漂移；destroy 的 stop/disable/reload 失败只记警告，不算） |
| 2 | 输入错误，**什么都没改动**（包目录无 `xac.yaml`、yaml 不合法、参数错、非 root、destroy 未确认） |

`2` 可以安全重试，`1` 需要人看一眼。

`logs` 的两种失败都退 2：`-n` 非正整数（输出 `-n must be a positive integer`），以及 `journalctl` 起不来（这台机器没有 journalctl）。
理由同「什么都没改动」——用户没拿到日志，但机器状态没变，重跑无害。

### 6.7 输出格式

结果对象（`ApplyResult` / `StatusResult` / `DestroyResult`）与打印分离，将来加 `--json` 只需新增
Formatter。v1 不实现。

运行时输出（stdout/stderr、`--help`、校验与警告）统一英文 ASCII，便于 `LANG` 为空的服务器与 CI
日志；代码注释与文档正文仍可用中文。

## 7. 测试策略

### 7.1 单元测试（不碰真实 systemd）

- **渲染器**：给定 ServiceSpec 断言输出逐字节；覆盖 `%` 转义、含空格参数加引号、`${VAR}` 原样
  保留、`[Install]` 段的有无、`restart` 取值透传、两个秒数的缺省
- **校验器**：规范 §6 每条规则各一组正反例，断言 `Violation` 的路径与消息
- **schema**：未知字段、类型错、嵌套路径错误
- **Action**：每个动作单独测（临时目录 + `RecordingRunner`）
- **env 文件**：含 `=`、`#`、空格、`$`、`%` 的值正确往返

### 7.2 集成测试（不依赖容器）

`RecordingRunner` 预置 systemctl 输出，临时目录当 `/etc/systemd/system` 与 `/etc/xac`。
端到端：apply → 断言文件内容与命令序列 → status 报 in sync → destroy → 断言全部清理干净。
这一层只跑 `ProcessBuilder` 与文件系统，**不启动 Docker**；`mvn test` 必须始终在没有
Docker 的机器上通过。真 systemd 场景见 7.3 的 `SmokeIT`。

### 7.3 真 systemd 验证

**已实测两轮**（第一轮 Ubuntu 22.04.5 + systemd 249 做 systemd 侧探针；第二轮 **Ubuntu 24.04 +
systemd 255 + JRE 21 跑真实 jar**，即目标平台）。容器以
`--privileged --cgroupns=host --tmpfs /run --tmpfs /run/lock --tmpfs /tmp` 启动、`/sbin/init` 作
PID 1。24.04 上 `systemctl is-system-running` 返回 `running`；249 上返回 `degraded`（有单元失败），
因此等待条件仍应接受 `running | degraded`。

这两轮的事实现在由可重复的 JUnit IT 固定：`SmokeIT`（JUnit 5 + Testcontainers，源码在
`java-systemd-service-as-code/src/test/java/io/xac/systemd/e2e/`）在一个一次性容器里跑完
`apply / status / logs / stop / destroy`、path-links 四种护栏、重复 apply、`--dry-run`
与 shadow guard。命令是 `mvn -f java-systemd-service-as-code/pom.xml -Pe2e verify`；
它由 `e2e` profile 里的 failsafe 绑定到 `integration-test` / `verify`，先打 fat jar 再起容器。
`mvn test` 不启动 Docker，也不匹配 `SmokeIT`。镜像缺失时按 `e2e/README.md` 构建。

| 已验证的事实 | 结论 |
|---|---|
| unit 加载 | `daemon-reload` 退出 0，`LoadState=loaded` |
| `enable` / `disable` | 建/删 `multi-user.target.wants/xac.<name>.service`，`UnitFileState` 同步 |
| `${VAR}` 词内展开 | 展开且**不切词**（值含空格仍是一个 argv）→ `ExecStart` 保持 argv 列表，不用 shell |
| `%%` → `%` | 成立（`-Dpct=100%%` → 进程收到 `-Dpct=100%`，`/proc/<pid>/cmdline` 逐字节确认） |
| 未转义 `%n` | 被替换成 unit 名且**不报错** → 渲染期必须全量转义 `%` |
| `$$` → `$` | 成立 |
| `${未定义}` | **静默变空串**，不拒绝启动 → 静态校验是唯一防线 |
| `Restart=on-failure` + `RestartSec` | SIGKILL 后 `NRestarts=1`、自动拉起、`ActiveState=active`；崩溃循环到第 5 次按默认 `StartLimit` 停止并 `failed` |
| `-Xmx512m` 真的生效 | 进程内 `Runtime.maxMemory()` = 518979584（≈494 MiB，512 MiB 减 survivor） |
| `WorkingDirectory` | 进程 `cwd` = `/opt/xac-spike/work`，`user.dir` 一致 |
| `EnvironmentFile` | env 文件改值后 `restart` 立即生效，无需改 unit；值含空格完整保留 |
| `--release 8` 的 jar 跑在 JDK 21 | 正常启动运行 |
| **`SuccessExitStatus=143`** | 不写：`systemctl stop` 后 `ActiveState=failed`、`Result=exit-code`、`ExecMainStatus=143`；写入：`inactive/dead`、`Result=success` |
| `auto-start: false`（无 `[Install]`） | `systemctl enable` 打印说明但**退出 0**、`UnitFileState=static`；`disable` 仍能清掉旧的 wants 链接 |
| `show` 一个不存在的 unit | 退出 0，`LoadState=not-found`（status 据此判定未安装） |
| `stop` / `disable` 不存在的 unit | 退出码分别为 **5** 和 **1** → destroy 里按「只警告」处理 |

**未验证**：systemd 219（CentOS 7）——已按 D17 移出支持范围，不再验证。

复现方式（现在由 `SmokeIT` 自动执行，下面的 docker 命令是手工等价物）：

```dockerfile
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      systemd systemd-sysv dbus procps openjdk-21-jre-headless \
 && rm -rf /var/lib/apt/lists/*
STOPSIGNAL SIGRTMIN+3
```
```bash
docker run -d --name xac-e2e --privileged --cgroupns=host \
  --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
  --entrypoint /sbin/init xac-e2e:24.04
```
被测 jar 在开发机用 `--release 8` 编译；`SmokeIT` 从测试类 `DemoApp` 的 class 文件现场打一个
可运行 jar，连同 CLI fat jar 与生成的 `xac.yaml` 用 `copyFileToContainer` 放进 `/opt/demo`，
与真实部署模型一致（CLI 不上传产物）。测试还会显式把 entrypoint 覆写为 `/sbin/init` 并清空
CMD：本机缓存的镜像元数据可能是 `ENTRYPOINT ["/bin/sh"]`，不覆写会变成 `/bin/sh /sbin/init`。

## 8. 构建与交付

- Maven，`maven-compiler-plugin` 配 `<release>8</release>`
- maven-shade-plugin 打 fat jar；maven-assembly-plugin 打发行包（必须排在 shade 之后，包里才是 fat jar）
- 产物：
  - `java-systemd-service-as-code/target/java-systemd-service-cli-<ver>.jar`（可直接 `java -jar`）
  - `java-systemd-service-as-code/target/java-systemd-service-cli-<ver>.tar.gz`，顶层目录
    `java-systemd-service-cli-<ver>/` 下 `bin/java-systemd-service-cli`（POSIX sh，`0755`）与
    `libs/java-systemd-service-cli-<ver>.jar`（`0644`）
- 入口脚本按 `XAC_JAVA` → `$JAVA_HOME/bin/java` → `PATH` 选 JVM，按自身真实路径定位 `../libs/*.jar`，
  再 `exec java -jar ... "$@"`；启动器自身的失败（找不到 jar 或 JVM）退出 1，其余退出码透传 CLI
- 启动脚本先于 jar 拦截 `install` / `uninstall`：把自身真实路径软链到
  `/usr/local/bin/java-systemd-service-cli`（固定位置，无参数），需要 root。只替换或删除带
  `# xac-launcher` 标记的启动器；普通文件、指向别处的软链一律拒绝（退出 2）。建链后跑一次
  `--version` 与 jar 自己的输出比对，不一致就回滚：新建的删除，升级的恢复旧指向。
- release 只发布 `tar.gz`；`--version` 必须等于 tag 去掉 `v` 前缀（CI 里解压后实际跑一次校验）
- 仓库：`D:\Develop\xac` 为 monorepo 根，`.git` 初始化在根

## 9. 错误与诊断

- 校验失败：列出**全部**违规（不只第一条），每条含字段路径、期望、实际
- 执行失败：打印失败的命令、退出码、stderr 摘要，并给出下一步建议
- 非 root：直接提示 `apply / destroy require root`（退出码 2，什么都没改）

## 10. 术语

| 术语 | 含义 |
|---|---|
| 包目录 | 放 `xac.yaml` 与该服务文件的目录，命令的参数 |
| 期望状态 | 由 `xac.yaml` 渲染出的 unit / env / 链接 |
| 实际状态 | 本机当前的文件内容与 systemd 状态 |

## 11. 已知限制

- 服务以 root 运行（未支持 `User=`）
- 无 systemd 加固
- 仅支持 systemd，不兼容 OpenRC / SysV
- 不支持应用启动参数
- 无 `diff` / `render`
- `apply` 无条件重启，无法避免短暂停机
- status 不显示内存/CPU
- `destroy` 不删包目录与 workdir；`path-links` 不再产生任何备份，遇到既有目标直接报错交给人处理
- `destroy` 只处理带管理头的 unit 文件；手工删掉 unit 文件后仍在跑的进程不会被停掉（只警告）
- 符号链接形式的「管理 unit」会被 destroy 整体跳过（xac 自己从不这样写 unit）
- `path-links` 的受保护目录策略是保守的：`/var`、`/home` 下面也不能建链接（需要时先改用其它位置）
- 不防恶意 `xac.yaml`：`runtime.java` 与 `opts` 等价于「以 root 执行任意命令」，`xac` 只保证
  「人写错或不一致时不波及系统」，不承担权限隔离职责

## 12. 后续（不在 v1）

`diff`、`render`、`restart`、`--json`、`--no-restart`、`hardening` 段、`User=` 支持、
多环境、其他 `*-as-code` 子项目（届时把 `spec` / `exec` 抽为 `xac-core`）。
