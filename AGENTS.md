# AGENTS.md

本文件是本仓库的长期约定，对任何在本仓库工作的 agent 生效（人同样适用）。

## 项目

`xac`（x-as-code）：用代码定义和管理一切。首个子项目 `java-systemd-service-as-code`：
用一个 `xac.yaml` 描述一个跑在 systemd 上的 Java 服务，用 `xac` 命令行管理它的全生命周期。

- 仓库根：设计文档 `docs/superpowers/`，子项目在 `java-systemd-service-as-code/`。
- 目标平台：Ubuntu 24.04 + systemd 255。不兼容 CentOS 7 / systemd 219。
- 设计文档是实现的准绳：`docs/superpowers/specs/2026-11-10-java-systemd-service-as-code-design.md`。
  DSL 规范：`java-systemd-service-as-code/docs/xac-yaml-spec.md`。

## 硬性禁止

1. **禁止使用 git worktree。** 所有工作直接在 `D:\Develop\xac` 的主工作区里做，需要并行就在分支上串行推进。历史上用过 `xac-impl` worktree，已废弃。
2. **禁止修改 `java-systemd-service-as-code/docs/service-yaml-spec.md`。** 这是立项时的原始参考稿，只读；新规范写在 `xac-yaml-spec.md`。
3. **禁止自动化地移动或删除用户数据。** `path-links` 遇到已存在的目标（真实目录、普通文件、指向别处的软链接、悬空链接）一律报错退出 2，交给人处理；不允许「备份成 `.bak` 再建链接」这类自动让路行为。
4. **禁止让破坏性动作先执行、后警告。** 归属判断、路径护栏必须发生在任何写入之前；无法证明归属就跳过并警告，绝不先 `systemctl stop` 再补一条警告。
5. 不要用问答弹窗向用户提问；问题写在正文里。

## 代码约束

- **Java 8**：`maven.compiler.release=8`。禁止 record、`var`、switch 表达式、文本块、
  `List.of`/`Map.of`、`Optional.isEmpty()`、`ProcessorHandle`/`ProcessHandle`、`Stream.toList()`。
  测试代码同样按 `--release 8` 编译。
- 依赖白名单（0.2.3 现状）：运行期只有 picocli 4.7.6、snakeyaml 2.2 与 Lanterna 3.1.5
  （`com.googlecode.lanterna:lanterna`，仅 `tui` 命令使用；shade 不得声明
  filters/includes 过滤 Lanterna 资源（3.1.5 无 terminfo，Linux 走 stty））；
  测试用 JUnit 5.10.2 与 Testcontainers
  1.19.8（`org.testcontainers:testcontainers`、`org.testcontainers:junit-jupiter`）；
  打包用 maven-shade-plugin 与 maven-assembly-plugin，e2e 用 maven-failsafe-plugin。
  不新增其他依赖，不追大版本。
- 外部命令一律 `ProcessBuilder` + `List<String>` 传 argv，**不经过 shell**，不做字符串拼接。
- 渲染必须确定性：同样的 `xac.yaml` 产出逐字节相同的 unit 与 env 文件。
- 运行时输出（stdout/stderr、`--help`、校验与警告）统一英文 ASCII；注释与文档正文不限。

## 分层

代码分三层，依赖只能向下：

- **入口层**：`cli`（picocli 命令、文案、退出码）与 `tui`（Lanterna 画面、键位、视图模型）。
  `tui` 只依赖 `core`，不得 import `cli`；展示时可直接读 `spec` / `status` 等低层的不可变结果
  类型（如 `StatusResult`），但 systemd 动作编排只能经 `core` / `apply`。`cli` 允许 import `tui`，
  仅限 `RootCommand` 注册 `tui` 子命令这一处（picocli 必需），不调用 TUI 的渲染与视图代码；
  除此之外两层互不依赖。
- **门面层**：`core`。`ServicePackage` 是唯一门面，只返回数据与英文事实描述；
  不出现 `System.out`/`PrintStream`、退出码或中文用户文案。`core` 只依赖
  `spec`、`systemd`、`exec`、`apply`、`status`、`logs`。
- **基础层**：`spec`（解析、Schema、校验、注释与 yaml 文本）、`systemd`（unit/env 渲染与路径）、
  `exec`（进程执行）。`spec` / `systemd` / `exec` 不得反向依赖 `cli`、`tui` 或 `core`。

`apply` / `status` / `logs` 依赖 `spec` / `systemd` / `exec`，不依赖 `cli` / `tui` / `core`。
新入口只允许依赖 `core`；需要新能力时加在 `core` 或更下层，不要复制编排，也不要让
`core` 反向依赖入口层。渲染保持纯函数、确定性输出。

## 命令

```bash
# 全部单元/集成测试（约 10 秒，不需要 root、不需要 systemd）
mvn -f java-systemd-service-as-code/pom.xml test

# 打包：fat jar + 发行包
mvn -f java-systemd-service-as-code/pom.xml -DskipTests package
#   → java-systemd-service-as-code/target/java-systemd-service-cli-0.2.3.jar
#   → java-systemd-service-as-code/target/java-systemd-service-cli-0.2.3.tar.gz
#      解压后 java-systemd-service-cli-0.2.3/{bin/java-systemd-service-cli, libs/*.jar}

# 挂到 PATH（root；只操作 /usr/local/bin 下自己那条软链）
sudo java-systemd-service-cli-0.2.3/bin/java-systemd-service-cli install
sudo java-systemd-service-cli-0.2.3/bin/java-systemd-service-cli uninstall

# 交互式生成 xac.yaml 模板（父目录 → <父目录>/<服务名>/xac.yaml；不需要 root，不覆盖已有文件）
java -jar java-systemd-service-as-code/target/java-systemd-service-cli-0.2.3.jar init /tmp

# 交互式终端界面（需要真实 TTY，仅 Linux/SSH 终端；非 TTY 一行提示并退 2）
java -jar java-systemd-service-as-code/target/java-systemd-service-cli-0.2.3.jar tui /opt/demo

# 真 systemd e2e（Testcontainers；需要 Docker + 镜像 xac-e2e:24.04，见 e2e/README.md）
mvn -f java-systemd-service-as-code/pom.xml -Pe2e verify
#   镜像缺失时先构建：cd java-systemd-service-as-code && docker build -t xac-e2e:24.04 e2e
```

TUI 测试分两层：

- 单测不启终端：`Display` / `KeySource` 注入假实现（`tui/RecordingDisplay`、
  `tui/ScriptedKeySource`）；Lanterna 适配层用 `LanternaDisplayTest`（动态代理 + `RecordingScreen`）、
  `LanternaKeySourceTest`（匿名 `InputProvider`）、`CanvasRoleTest` / `LanternaDisplayRoleTest`
  （角色与颜色映射）验证。
- e2e 驱动真实 PTY：`SmokeIT#tuiRendersInARealPtyAndQuits` / `#tuiApplyRunsTheRealLifecycle`
  用 `script` 分配 pty，按键走 FIFO 且「等屏幕出现预期文本再送下一个键」；
  `SmokeIT#tuiScreensMatchDesignSnapshots` 用 `AnsiScreen` 回放 ANSI 成字符网格，比对
  `src/test/resources/tui-snapshots/*.txt`（日志正文与空行按内容过滤；
  `-Dxac.updateSnapshots=true` 更新后必须人工 diff）。真 PTY 尺寸固定 80×24：Lanterna 用 DSR
  查询光标位置测尺寸，`script` 的 pty 不应答，`stty` / `COLUMNS` 无效；窄屏/矮屏走单测。
- 截图不参与判红：`TuiScreenDump` + `e2e/screens.py` 生成 PNG 供人工审图（见 `CONTRIBUTING.md`）。

## 安全语义（改代码前先读）

`xac` 在目标机上以 root 运行，任何「路径来自 yaml、直接落到文件系统」的地方都要有护栏：

- 写 unit / env 前：路径必须是普通文件或不存在；文件首行不是管理头就拒绝覆盖。
- `apply` 前查同名 unit 是否由系统提供（`systemctl show` 的 `FragmentPath`），是则拒绝。
- `destroy` 只对自己拥有的 unit（普通文件 + 管理头）执行 `stop`/`disable`，只删指向配置源的软链接。
- `install` / `uninstall`（启动脚本里）只动 `/usr/local/bin/java-systemd-service-cli` 这一条软链，
  且目标必须是带 `# xac-launcher` 标记的启动器；外来文件或陌生软链退 2。
- 破坏性分支一律返回退出码 2（什么都没改动），不要用 1。

## 提交

- 提交信息用中文，`类型: 摘要`，类型取 `feat` / `fix` / `test` / `docs` / `build` / `chore`。
- 每个提交都要能独立构建通过；测试绿再提交。
- 设计口径变了要同步设计文档，不允许代码与文档分叉。
