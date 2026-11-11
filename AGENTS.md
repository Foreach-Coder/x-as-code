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
- 运行期依赖只有 picocli 4.7.6 与 snakeyaml 2.2；测试用 JUnit 5.10.2 与 Testcontainers
  1.19.8（`org.testcontainers:testcontainers`、`org.testcontainers:junit-jupiter`）；
  打包用 maven-shade-plugin，e2e 用 maven-failsafe-plugin。不新增其他依赖，不追大版本。
- 外部命令一律 `ProcessBuilder` + `List<String>` 传 argv，**不经过 shell**，不做字符串拼接。
- 渲染必须确定性：同样的 `xac.yaml` 产出逐字节相同的 unit 与 env 文件。
- 运行时输出（stdout/stderr、`--help`、校验与警告）统一英文 ASCII；注释与文档正文不限。

## 命令

```bash
# 全部单元/集成测试（约 10 秒，不需要 root、不需要 systemd）
mvn -f java-systemd-service-as-code/pom.xml test

# 打包成 fat jar
mvn -f java-systemd-service-as-code/pom.xml -DskipTests package
#   → java-systemd-service-as-code/target/java-systemd-service-cli-0.1.0.jar

# 真 systemd e2e（Testcontainers；需要 Docker + 镜像 xac-e2e:24.04，见 e2e/README.md）
mvn -f java-systemd-service-as-code/pom.xml -Pe2e verify
#   镜像缺失时先构建：cd java-systemd-service-as-code && docker build -t xac-e2e:24.04 e2e
```

## 安全语义（改代码前先读）

`xac` 在目标机上以 root 运行，任何「路径来自 yaml、直接落到文件系统」的地方都要有护栏：

- 写 unit / env 前：路径必须是普通文件或不存在；文件首行不是管理头就拒绝覆盖。
- `apply` 前查同名 unit 是否由系统提供（`systemctl show` 的 `FragmentPath`），是则拒绝。
- `destroy` 只对自己拥有的 unit（普通文件 + 管理头）执行 `stop`/`disable`，只删指向配置源的软链接。
- 破坏性分支一律返回退出码 2（什么都没改动），不要用 1。

## 提交

- 提交信息用中文，`类型: 摘要`，类型取 `feat` / `fix` / `test` / `docs` / `build` / `chore`。
- 每个提交都要能独立构建通过；测试绿再提交。
- 设计口径变了要同步设计文档，不允许代码与文档分叉。
