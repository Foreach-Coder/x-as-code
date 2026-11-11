# xac（x-as-code）

用代码定义和管理一切。仓库里每个子项目都是「把某类基础设施写成代码」：

| 子项目 | 状态 | 一句话 |
|---|---|---|
| [`java-systemd-service-as-code`](java-systemd-service-as-code) | **v0.1.0** | 用一个 `xac.yaml` 描述跑在 systemd 上的 Java 服务，用 `xac` 命令管它的全生命周期 |

## 这是什么

`xac` 是一个命令行工具。你在服务器上放一个包目录（业务 jar + `xac.yaml`），然后：

```bash
sudo java -jar xac.jar apply .
```

它会渲染 systemd unit 与环境变量文件、建立配置里声明的软链接，然后 `daemon-reload`、按需 `enable`、`restart`。`status` 检查有没有漂移，`logs` 看日志，`destroy` 把服务拆干净。

设计目标是**像检查代码一样检查服务状态**：`status` 退出码 `0` 表示「配置与机器一致且服务在运行」，可以直接放进 CI 或巡检脚本。没有 `diff`、没有增量同步、没有远程 SSH——重跑 `apply` 永远把机器收敛到 `xac.yaml` 描述的状态。

## 现状

| 项 | 值 |
|---|---|
| 版本 | `v0.1.0` |
| 目标平台 | Ubuntu 24.04 + systemd 255（不支持 CentOS 7 / systemd 219） |
| 运行要求 | root、systemd；目标机只需 JRE 8+ |
| 产物 | 单文件 fat jar，约 `0.8 MB` |
| 测试 | 156 个单元/集成测试；9 个 Testcontainers 端到端用例（真 systemd 255） |

## 快速开始

### 1. 构建

```bash
mvn -f java-systemd-service-as-code/pom.xml -DskipTests package
# → java-systemd-service-as-code/target/java-systemd-service-cli-0.1.0.jar
```

### 2. 在目标机上准备包目录

包目录必须包含 `xac.yaml`；`artifact.jar` 与 `path-links` 的源都相对包目录解析。

```
/opt/demo/
├── app.jar
├── conf/            # 可选，供 path-links 使用
└── xac.yaml
/opt/xac.jar         # 把 fat jar 拷到服务器
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

lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 5
  timeout-stop-sec: 30
```

### 3. 管理服务

```bash
cd /opt/demo
sudo java -jar /opt/xac.jar apply   .          # 安装/更新并重启
     java -jar /opt/xac.jar status  .          # 只读，0=一致 1=漂移
     java -jar /opt/xac.jar logs    . -n 100 -f
sudo java -jar /opt/xac.jar destroy . -y       # 停止并清理
```

`apply` 和 `destroy` 支持 `--dry-run`，只打印将执行的动作。

## 命令

| 命令 | 参数 | 说明 |
|---|---|---|
| `apply` | `[包目录] [--dry-run]` | 渲染并写入 unit/env、建链接、`daemon-reload`、`enable\|disable`、`restart` |
| `status` | `[包目录]` | 只读；比对磁盘与期望、查询 `systemctl show`；一致退 0，有漂移退 1 |
| `logs` | `[包目录] [-n N] [-f]` | `journalctl -u xac.<name>.service --no-pager`；`-f` 跟随 |
| `destroy` | `[包目录] [--dry-run] [-y]` | 停止、禁用、删 unit/env/软链接；非交互环境必须加 `-y` |

退出码：`0` 成功；`1` 执行失败或 `status` 检出漂移；`2` 输入/环境问题且**什么都没改动**。

## 生成什么

服务名自动加 `xac.` 前缀，避免与系统服务撞名：

| 路径 | 权限 | 内容 |
|---|---|---|
| `/etc/systemd/system/xac.<name>.service` | `0644` | 渲染出的 unit，首行 `# Managed by xac. Do not edit.` |
| `/etc/xac/xac.<name>.env` | `0600` | `env` 段渲染的 `KEY="value"` 文件，仅 root 可读 |
| `/etc/systemd/system/multi-user.target.wants/…` | — | `auto-start: true` 时的 enable 链接 |

`ExecStart` 顺序固定为 `<java> <opts…> <-Dk=v…> -jar <jar>`；`${VAR}` 原样写进 unit，由 systemd 在运行时展开，密码不会出现在 `0644` 的 unit 里。

## 安全约束

`xac` 以 root 运行，因此所有「路径来自 `xac.yaml`」的地方都有护栏：

- 目标机上已有同名 systemd unit（如系统的 `ssh.service`）→ 拒绝，退出 2。
- unit/env 文件已存在但不是 xac 写的（无管理头）→ 拒绝覆盖或删除。
- `path-links` 的目标已存在（真实目录/文件/指向别处的软链接/悬空链接）→ 报错退出 2，交给人处理，绝不自动移动或备份用户数据。
- `destroy` 只对自己拥有的 unit 执行 `stop`/`disable`，只删指向配置源的软链接。

明确不防：恶意 `xac.yaml` 可以通过 `runtime.java` 或 `opts` 执行任意命令——能改 `xac.yaml` 的人本来就等价于 root。

## 测试

```bash
# 单元 + 集成测试，不需要 root、不需要 systemd、不启动 Docker
mvn -f java-systemd-service-as-code/pom.xml test

# 真 systemd 端到端（需要 Docker；镜像见 java-systemd-service-as-code/e2e/README.md）
cd java-systemd-service-as-code
docker build -t xac-e2e:24.04 e2e
mvn -f pom.xml -Pe2e verify
```

e2e 用 Testcontainers 起一个 `--privileged` 的 Ubuntu 24.04 容器，`systemd` 作为 PID 1，覆盖 apply/status/logs/destroy、幂等重跑、path-links 拒绝、影子 unit 拒绝、`SuccessExitStatus=143` 等场景。

## 已知限制

- 服务以 **root** 运行，暂不支持 `User=`/`Group=`。
- CLI 必须**在目标机上**执行，没有远程编排；需要 root。
- 不负责产物分发：业务 jar 与配置由 CI/rsync 事先放好。
- 单服务：没有项目层编排、服务间依赖或分组。
- 仅支持 systemd；不兼容 OpenRC / SysV，不支持 systemd 219 及更早。

## 文档

| 文档 | 内容 |
|---|---|
| [`java-systemd-service-as-code/docs/xac-yaml-spec.md`](java-systemd-service-as-code/docs/xac-yaml-spec.md) | DSL 规范（字段、默认值、校验规则） |
| [`docs/superpowers/specs/2026-11-10-java-systemd-service-as-code-design.md`](docs/superpowers/specs/2026-11-10-java-systemd-service-as-code-design.md) | 设计文档：决策、unit 渲染、命令行为、测试策略 |
| [`AGENTS.md`](AGENTS.md) | 仓库长期约定（代码约束、安全语义、提交规范） |
