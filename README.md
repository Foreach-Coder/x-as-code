# xac（x-as-code）

用代码定义和管理一切。仓库里每个子项目都是「把某类基础设施写成代码」：

| 子项目 | 状态 | 一句话 |
|---|---|---|
| [`java-systemd-service-as-code`](java-systemd-service-as-code) | **v0.2.2** | 用一个 `xac.yaml` 描述跑在 systemd 上的 Java 服务，用 `java-systemd-service-cli` 管它的全生命周期 |

## 这是什么

`java-systemd-service-cli` 是一个在目标机上执行的命令行工具。你在服务器上放一个包目录（业务 jar + `xac.yaml`），然后：

```bash
sudo java-systemd-service-cli apply /opt/demo
```

它会渲染 systemd unit 与环境变量文件、建立配置里声明的软链接，然后 `daemon-reload`、按需 `enable`、`restart`。`status` 检查有没有漂移，`logs` 看日志，`destroy` 把服务拆干净。

设计目标是**像检查代码一样检查服务状态**：`status` 退出码 `0` 表示「配置与机器一致且服务在运行」，可以直接放进 CI 或巡检脚本。没有 `diff`、没有增量同步、没有远程 SSH——重跑 `apply` 永远把机器收敛到 `xac.yaml` 描述的状态。

## 安装

要求：Ubuntu 24.04 + systemd 255；目标机上有一个 JRE（8 及以上）；以 root 执行 `apply` / `destroy`。

从 [Releases](https://github.com/Foreach-Coder/x-as-code/releases) 下载发行包，解压到任意目录（下文用 `/opt/xac`），再把启动脚本挂到 PATH：

```bash
curl -LO https://github.com/Foreach-Coder/x-as-code/releases/download/v0.2.2/java-systemd-service-cli-0.2.2.tar.gz
tar -xzf java-systemd-service-cli-0.2.2.tar.gz
sudo mkdir -p /opt/xac && sudo cp -r java-systemd-service-cli-0.2.2/* /opt/xac/

sudo /opt/xac/bin/java-systemd-service-cli install
java-systemd-service-cli --version
```

`install` 只做一件事：把 `/usr/local/bin/java-systemd-service-cli` 软链到当前发行包的启动脚本，并用 `--version` 校验；校验不过会恢复原指向。它不写别的文件，遇到外来文件或陌生软链直接拒绝（退出码 2）。

启动脚本按 `XAC_JAVA` → `$JAVA_HOME/bin/java` → `PATH` 的顺序找 JVM，所以不需要 JDK，只要有 JRE。

## 快速开始

### 1. 生成 `xac.yaml`（可选）

不想手写配置，可以让 CLI 交互式生成一份带注释的模板。参数是**父目录**，服务名在交互里问，最终落在 `<父目录>/<服务名>/xac.yaml`：

```bash
java-systemd-service-cli init /opt
# service name [demo]:
# artifact jar [app.jar]:
# java executable [/usr/bin/java]:
# → created /opt/demo/xac.yaml
```

已有同名 `xac.yaml`、目标目录非空或服务名不合法时会重新提问，不覆盖任何文件。

### 2. 准备包目录

包目录必须包含 `xac.yaml`；`artifact.jar` 与 `path-links` 的源都相对包目录解析。

```
/opt/demo/
├── app.jar
├── conf/            # 可选，供 path-links 使用
└── xac.yaml
```

`xac.yaml` 示例：

```yaml
name: demo
description: demo service

artifact:
  jar: app.jar

runtime:
  java: /usr/bin/java
  opts:
    - -Xmx256m
    - -XX:+UseSerialGC

env:
  GREETING: hello world

properties:
  server.port: "8080"
  greeting: ${GREETING}

workdir: /opt/demo/work

path-links:
  - ./conf:/opt/demo/conf

# 服务不往控制台打日志、自己写日志文件时，让 logs 读它；不写这段则读 journald。
# logs:
#   file: /var/log/demo/app.log

lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 5
  timeout-stop-sec: 30
```

字段含义、默认值与校验规则见 [`xac-yaml-spec.md`](java-systemd-service-as-code/docs/xac-yaml-spec.md)。

### 3. 预览并安装

```bash
cd /opt/demo
sudo java-systemd-service-cli apply . --dry-run   # 只打印会做什么
sudo java-systemd-service-cli apply .             # 写入文件、reload、enable、restart
```

`apply` 是幂等的：不管当前是什么状态，每次都重建 unit/env、重跑 `daemon-reload`、`enable|disable`、`restart`。生产环境重复执行也会重启服务，介意的话用 `--dry-run` 先看计划。

### 4. 日常运维

```bash
java-systemd-service-cli status .              # 只读，0=一致 1=漂移
java-systemd-service-cli logs . -n 100         # 最近 100 行（journald，或 logs.file 指定的文件）
java-systemd-service-cli logs . -f             # 跟随
sudo java-systemd-service-cli destroy .        # 停止并清理（交互确认）
sudo java-systemd-service-cli destroy . -y     # 非交互环境用 -y
```

`logs` 默认读 `journalctl -u xac.<name>`。如果服务自己不往控制台打日志，在 `xac.yaml` 里写
`logs.file: <路径>`（相对路径按包目录解析），`logs` 就改读那个文件（`-n` / `-f` 一样可用）；文件不存在时退 2。
`xac` 不创建也不轮转这个文件。文件 follow 没有 shutdown hook，Ctrl-C 时可能丢掉最后一行
没有换行符的残余。

升级 CLI 本身：解压新版本到新目录，在新目录里再跑一次 `sudo .../bin/java-systemd-service-cli install`，链接自动改指；旧目录随后可以删除。不需要先 `uninstall`。

## 命令

| 命令 | 参数 | 说明 |
|---|---|---|
| `apply` | `[包目录] [--dry-run]` | 渲染并写入 unit/env、建 `path-links`、`daemon-reload`、`enable\|disable`、`restart` |
| `status` | `[包目录]` | 只读；比对磁盘与期望、查询 `systemctl show`；一致退 0，有漂移退 1 |
| `logs` | `[包目录] [-n N] [-f]` | 读 `journalctl -u xac.<name>.service --no-pager`，或 `logs.file` 指定的文件；`-f` 跟随 |
| `destroy` | `[包目录] [--dry-run] [-y]` | 停止、禁用、删 unit/env/软链接；非交互环境必须加 `-y` |
| `init` | `[父目录]` | 交互式创建 `<父目录>/<服务名>/xac.yaml` 模板，不覆盖已有文件 |
| `tui` | `[包目录]` | 交互式终端界面（Linux/SSH 终端）；没有 TTY 时一行提示并退 2 |
| `install` | `--dry-run` | 把当前发行包的启动脚本软链到 `/usr/local/bin`（root） |
| `uninstall` | `--dry-run` | 删掉 `install` 建的那条软链（root） |

包目录缺省为当前目录。`apply` / `destroy` 需要 root；`status` / `logs` 不需要（读 journald 时取决于是否在 `systemd-journal` 组，读文件时取决于文件的读权限）。

退出码：`0` 成功；`1` 执行失败或 `status` 检出漂移；`2` 输入/环境问题且**什么都没改动**。

## TUI

`java-systemd-service-cli tui <package-dir>` 在终端里提供状态面板、动作确认和 `xac.yaml` 行编辑，适合 SSH 上快速查看和操作。

```bash
sudo java-systemd-service-cli tui /opt/demo   # 可执行 apply / restart / destroy
java-systemd-service-cli tui /opt/demo        # 非 root：不能 apply/restart/destroy，状态、日志、编辑仍可用
```

需要真实 TTY 且 `TERM` 可用。`System.console()` 为空（管道、CI、cron）或 `TERM` 为空/`dumb`/`unknown` 时，
stderr 打印 `tui requires an interactive terminal` 并退出 2；`xac.yaml` 本身无效时打印校验错误并退出 2。
仅支持 Linux（systemd）和常规 SSH 终端。

### 主屏

标题栏显示服务名、活动状态（`running` / `stopped` / `failed` / `unknown`）、`auto-start on|off`、`root` / `not root`。
下面四行状态区，行首 `!` 表示该行有漂移：

| 行 | 可能的值 | 含义 |
|---|---|---|
| `unit` | `in sync` / `out of sync` / `missing` / `not loaded` | unit 文件与期望一致 / 不一致 / 不存在 / systemd 尚未加载 |
| `env` | `in sync` / `out of sync` / `missing` / `extra` / `not configured` / `cannot check` | env 文件一致 / 不一致 / 缺失 / 多出 / 未配置 / 配置了但当前用户读不了 |
| `process` | `systemctl show` 的 `ActiveState` 原值，或 `unknown` | `active` / `inactive` / `failed` / … |
| `links` | `ok` / `problems` | `path-links` 全部正常 / 有问题 |

`systemctl show` 查询失败时四行都是 `unknown`，失败原因显示在 `process` 行的详情里（不是底部 notice）。
CLI `status` 的 `(in sync)` / `(missing)` / `(cannot verify: root required)` 描述的是同一批事实；
TUI 里对应的写法是 `in sync` / `missing` / `cannot check`。

非 root 时标题栏是 `not root`：`a` / `r` / `d` 面板仍可打开，但不展示计划，只显示一行
`requires root`，`y` 不会执行（只读）；状态、日志和编辑仍可用。systemd 不可达时动作键不生效，
notice 提示 `actions disabled: systemd is not reachable`。

日志区默认在终端高度 ≥ 20 行时显示，来源是 `journald` 或 `xac.yaml` 的 `logs.file`。

| 按键 | 作用 |
|---|---|
| `j` / `↓`，`k` / `↑` | 日志向下 / 向上滚动一行 |
| `PgDn` / `PgUp` | 向下 / 向上翻一页 |
| `g` / `G`（`Home` / `End`） | 跳到日志顶部 / 底部 |
| `L` | 切换日志跟随（`follow` / `paused`） |
| `l` | 显示 / 隐藏日志区（窄屏默认隐藏） |
| `R` | 立即重读状态与日志 |
| `a` | 打开 apply 预览（逐条 `would …`），`y` 执行 |
| `r` | restart 确认，`y` 执行 |
| `d` | destroy 确认（列出 `will remove` / `keep` / `warning`），`y` 执行 |
| `e` | 编辑 `xac.yaml` |
| `?` | 帮助面板尚未实现，目前只显示 notice `help is not implemented yet` |
| `q` / `Esc` / `Ctrl-C` | 退出 |

动作在后台 daemon 线程里执行，底部显示 `running...`，完成后自动刷新；**不提供 detached 模式**。
执行期间按 `q` / `Esc` / `Ctrl-C` 会放弃等待并退出（当前这条外部命令可能跑完，剩余步骤不会再执行，
需要重新执行 `apply` / `destroy`）。

### 编辑 `xac.yaml`

`e` 打开全屏编辑。字段按 9 个节段组织，注释尽量保留；只改字段值，不提供自由文本编辑。

| 按键 | 作用 |
|---|---|
| `Tab` / `Shift-Tab` | 下一个 / 上一个节段 |
| `↑` / `↓` | 移动焦点 |
| `Enter` | 进入当前字段（多值字段进入行列表） |
| `→` / `l` | 仅多值字段：进入行列表 |
| `s` | 保存 |
| `u` | 撤销上一步（恢复整个模型快照） |
| `c` | 清空当前标量字段 |
| `Space` | 在 `true` / `false` / 未设置之间切换布尔字段 |
| `q` / `Esc` | 关闭；有未保存修改时先问 `y discard` / `n keep` |

行列表里 `↑` / `↓` 选行、`Enter` 编辑该行、`a` 加一行、`x` 删一行、`Esc` / `←` 返回；
编辑单行时 `Enter` 提交、`Esc` 取消、`Backspace` 删除。

保存时先校验再原子写入；校验失败时错误显示在对应字段上并聚焦第一个出错的字段。以下两种情况需要按两次 `s` 确认：

- 保存会丢弃无法保留的注释：`N comment(s) will be dropped; press s again to confirm`；
- 文件在编辑期间被外部改过：`file changed outside; press s again to overwrite`。

两者同时发生会合并成一条提示。保存成功后 notice 显示 `saved`（以及被丢弃的注释数），主屏重新加载状态和日志。
保存前没有逐行 diff 预览，只有上述二次确认。非 root 且文件不可写时保存会失败并显示错误。

## 生成什么

服务名自动加 `xac.` 前缀，避免与系统服务撞名：

| 路径 | 权限 | 内容 |
|---|---|---|
| `/etc/systemd/system/xac.<name>.service` | `0644` | 渲染出的 unit，首行 `# Managed by xac. Do not edit.` |
| `/etc/xac/xac.<name>.env` | `0600` | `env` 段渲染的 `KEY="value"` 文件，仅 root 可读 |
| `/etc/systemd/system/multi-user.target.wants/…` | — | `auto-start: true` 时的 enable 链接 |

`ExecStart` 顺序固定为 `<java> <opts…> <-Dk=v…> -jar <jar>`；`${VAR}` 原样写进 unit，由 systemd 在运行时展开，密码不会出现在 `0644` 的 unit 里。

## 安全约束

工具以 root 运行，因此所有「路径来自 `xac.yaml`」的地方都有护栏：

- 目标机上已有同名 systemd unit（如系统的 `ssh.service`）→ 拒绝，退出 2。
- unit/env 文件已存在但不是 xac 写的（无管理头）→ 拒绝覆盖或删除。
- `path-links` 的目标已存在（真实目录/文件/指向别处的软链接/悬空链接）→ 报错退出 2，交给人处理，绝不自动移动或备份用户数据。
- `destroy` 只对自己拥有的 unit 执行 `stop`/`disable`，只删指向配置源的软链接。
- `install` / `uninstall` 只动 `/usr/local/bin/java-systemd-service-cli` 这一条软链。

明确不防：恶意 `xac.yaml` 可以通过 `runtime.java` 或 `opts` 执行任意命令——能改 `xac.yaml` 的人本来就等价于 root。

## 已知限制

- 服务以 **root** 运行，暂不支持 `User=`/`Group=`。
- CLI 必须**在目标机上**执行，没有远程编排；`apply` / `destroy` 需要 root。
- 不负责产物分发：业务 jar 与配置由 CI/rsync 事先放好。
- 单服务：没有项目层编排、服务间依赖或分组。
- 仅支持 systemd；不兼容 OpenRC / SysV，不支持 systemd 219 及更早。

## 文档

| 文档 | 内容 |
|---|---|
| [`xac-yaml-spec.md`](java-systemd-service-as-code/docs/xac-yaml-spec.md) | DSL 规范（字段、默认值、校验规则） |
| [`docs/superpowers/specs/`](docs/superpowers/specs) | 设计文档：决策、unit 渲染、命令行为、测试策略 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 构建、测试、发布流程 |
| [`AGENTS.md`](AGENTS.md) | 仓库长期约定（代码约束、安全语义、提交规范） |
