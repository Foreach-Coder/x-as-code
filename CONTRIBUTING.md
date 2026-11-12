# 开发与发布

本文面向改这个仓库的人。使用说明在 [`README.md`](README.md)。

## 环境

- JDK 21（编译目标是 Java 8：`maven.compiler.release=8`）
- Maven 3.9+
- Docker（只有 e2e 需要；镜像是 Ubuntu 24.04 + systemd 255 + JRE 21）

运行时依赖只有 picocli 4.7.6、snakeyaml 2.2 与 Lanterna 3.1.5（`tui` 命令使用）；测试用
JUnit 5.10.2 与 Testcontainers 1.19.8；打包用 maven-shade-plugin 与 maven-assembly-plugin，
e2e 用 maven-failsafe-plugin。不新增依赖，不追大版本。

## 构建

```bash
mvn -f java-systemd-service-as-code/pom.xml -DskipTests package
# → java-systemd-service-as-code/target/java-systemd-service-cli-0.2.0.jar
# → java-systemd-service-as-code/target/java-systemd-service-cli-0.2.0.tar.gz
#    解压后：java-systemd-service-cli-0.2.0/{bin/java-systemd-service-cli, libs/java-systemd-service-cli-0.2.0.jar}
```

`bin/java-systemd-service-cli` 是 POSIX `sh` 启动脚本：先按 `XAC_JAVA` → `$JAVA_HOME/bin/java` → `PATH`
的顺序找 JVM，再 `exec java -jar ../libs/*.jar "$@"`；它还负责 `install` / `uninstall` 与 help 里的
launcher 段。脚本按自身真实路径定位 jar，所以可以软链到 `/usr/local/bin`。

模板资源是 `src/main/resources/xac.yaml.template`，由 `init` 读取并渲染。

## 测试

```bash
# 单元 + 集成测试：504 个，不需要 root / systemd / Docker
mvn -f java-systemd-service-as-code/pom.xml test

# 真 systemd 端到端：11 个用例，需要 Docker
mvn -f java-systemd-service-as-code/pom.xml -Pe2e verify
```

e2e 用 Testcontainers 起一个 `--privileged` 的 Ubuntu 24.04 容器，`systemd` 作为 PID 1，覆盖
apply/status/logs/destroy、幂等重跑、path-links 拒绝、影子 unit 拒绝、`SuccessExitStatus=143`、
`logs.file` 从文件读日志，以及 `tui` 在非 TTY 下退 2 等场景。
镜像缺失时先构建：

```bash
cd java-systemd-service-as-code
docker build -t xac-e2e:24.04 e2e
```

e2e 的细节（容器参数、产物怎么进去、`degraded` 状态的容忍）见
[`java-systemd-service-as-code/e2e/README.md`](java-systemd-service-as-code/e2e/README.md)。

Windows 上跑 `mvn -Pe2e verify` 需要 Docker Desktop（WSL2 后端），且 `docker version` 可用。

TUI 测试不启动真实终端：`TuiApp` / `EditScreen` / `StatusView` 等通过 `Display`、`KeySource` 抽象
注入 `RecordingDisplay` / `ScriptedKeySource`，视图模型用纯 JUnit 断言；Lanterna 适配层用
`DefaultVirtualTerminal` 验证，不需要 TTY。e2e 只覆盖非 TTY 退 2
（`SmokeIT#tuiRefusesToStartWithoutATty`），不做真实 PTY 全屏驱动。

## 目录

```
docs/superpowers/specs/           设计文档（实现以它为准）
docs/superpowers/plans/           历史实施计划（不再维护）
java-systemd-service-as-code/
├── docs/xac-yaml-spec.md         DSL 规范
├── docs/service-yaml-spec.md     立项时的原始参考稿，只读，不随实现改动
├── e2e/                          Dockerfile 与 e2e 说明
└── src/main/java/io/xac/systemd/
    ├── cli/                      picocli 命令（apply/status/logs/destroy/init/tui）与 Main
    ├── tui/                      Lanterna 主屏、键位、视图模型与编辑屏
    ├── core/                     ServicePackage 门面（入口层唯一依赖）
    ├── spec/                     xac.yaml 模型、schema 表、加载、校验与 yaml 文本
    ├── systemd/                  unit/env 渲染、命名与目标路径
    ├── apply/                    apply 与 destroy 的动作、预检、文件写入
    ├── exec/                     进程执行（CommandRunner）
    ├── status/                   systemctl show 解析与漂移判定
    └── logs/                     日志流（journalctl / 文件）
```

分层纪律：`cli` 与 `tui` 只依赖 `core` 且互不依赖；`core` 只依赖
`spec` / `systemd` / `exec` / `apply` / `status` / `logs`，不出现 `System.out`、退出码或用户文案；
`spec` / `systemd` / `exec` 不反向依赖上层。`exec` 包不出现 systemd 语义，`spec` 包不做进程执行；
渲染是纯函数、确定性输出。完整约定见 [`AGENTS.md`](AGENTS.md)。

## 代码约束

完整约定见 [`AGENTS.md`](AGENTS.md)。摘要：

- Java 8：禁止 record、`var`、switch 表达式、文本块、`List.of`/`Map.of`、`Optional.isEmpty()`、
  `ProcessHandle`、`Stream.toList()`；测试代码同样按 `--release 8` 编译。
- 外部命令一律 `ProcessBuilder` + `List<String>` 传 argv，不经过 shell。
- 运行时输出（stdout/stderr、`--help`、校验与警告）统一英文 ASCII；注释与文档正文不限。
- 提交信息中文、`类型: 摘要`；每个提交都要能独立构建；测试绿再提交；设计口径变了要同步设计文档。

## 发布

推一个 `v*` tag 即触发 [`.github/workflows/publish.yml`](.github/workflows/publish.yml)：

1. 跑 `mvn test`、打包 fat jar 与 `tar.gz`，解压发行包跑一次 `--version`，并自检 help、`init`、`install`/`uninstall`；
2. 创建 GitHub release（已存在则复用；缺 `tar.gz` 附件时补传，不覆盖同名附件）；
3. 把分支与 tag 镜像到 GitCode（`git push --prune`，不是 `--mirror`，避免把 `refs/remotes/*` 推过去）；
4. 在 GitCode 创建同名 release，用 Release API 上传同一个 `tar.gz`，校验 `sha256`，并同步 GitHub release 正文。

`push master` 只做第 3 步（镜像），不发版。

需要的唯一凭据是 GitHub 仓库 secret `GITCODE_FOREACH1991`（GitCode 个人访问令牌，勾选 repo 读写）：

1. 打开 <https://gitcode.com/setting/token-classic>，生成令牌并复制（只显示一次）；
2. 打开 <https://github.com/Foreach-Coder/x-as-code/settings/secrets/actions>，`New repository secret`，
   名字填 `GITCODE_FOREACH1991`，粘贴令牌。

补发步骤：先 `gh release edit <tag> --notes-file <file>` 改正文（可选），再
`gh workflow run publish.yml -f tag=vX.Y.Z`。

本地手动补发某个版本（等价于 workflow 第 3、4 步）：

```powershell
$env:GITCODE_TOKEN = '<token>'
git push --prune "https://foreach1991:$env:GITCODE_TOKEN@gitcode.com/foreach1991/x-as-code.git" `
  '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
pwsh -NoProfile -File .github/scripts/sync-gitcode-release.ps1 `
  -Owner foreach1991 -Repo x-as-code -Tag v0.2.0 `
  -Asset java-systemd-service-as-code/target/java-systemd-service-cli-0.2.0.tar.gz
```
