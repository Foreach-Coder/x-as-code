# e2e 环境

跑真 systemd 的容器环境，用来验证 `xac apply / status / destroy` 落到机器上的真实效果。
目标平台是 Ubuntu 24.04 + systemd 255（设计文档 D17）。

## 构建

```bash
docker build -t xac-e2e:24.04 .
```

## 启动

```bash
docker run -d --name xac-e2e \
  --privileged --cgroupns=host \
  --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
  --entrypoint /sbin/init \
  xac-e2e:24.04
```

`--privileged` 与 `--cgroupns=host` 是 systemd 作为 PID 1 在容器里工作的前提；
三个 `--tmpfs` 提供 systemd 需要的可写运行目录。

自检：

```bash
docker exec xac-e2e systemctl is-system-running
docker exec xac-e2e systemctl --version | head -1
docker exec xac-e2e java -version
```

## 被测产物怎么进去

jar 在开发机构建（`javac --release 8` / Maven），用 `docker cp` 或在 `docker run` 时挂载
进容器，与真实部署模型一致：**CLI 不上传产物**。

## 注意

- `systemctl is-system-running` 可能返回 `degraded`（容器里有必然失败的单元），
  断言要接受 `running | degraded`。
- 这个镜像只用于 e2e，不随 CLI 分发。

## 冒烟脚本

一条命令验证 fat jar 在真实 systemd 上的 `apply / status / logs / stop / destroy` 全流程。
`smoke.ps1` 是唯一承载断言的规范脚本；`smoke.sh` 只是转发到 `pwsh` 的薄入口。

```powershell
# Windows（本仓库已实际执行并通过：Windows PowerShell 5.1）
powershell -NoProfile -ExecutionPolicy Bypass -File e2e\smoke.ps1
```

```bash
# Linux / macOS 或 PowerShell 7+：按 pwsh 语义编写，本环境未实际执行
pwsh -NoProfile -File e2e/smoke.ps1
# 等价入口：只做 pwsh 转发，本环境未实际执行
bash e2e/smoke.sh
```

脚本做的事：

1. `mvn -q package` 产出 fat jar，并用 `javac --release 8` + `jar cfe` 打一个示例服务；
2. 起一次性容器（镜像缺省 `xac-e2e:24.04`，可用环境变量 `IMAGE` 覆盖；镜像不存在时
   按 `e2e/Dockerfile` 构建，耗时较长）；
3. 只通过 `docker cp` 把 CLI jar、`xac.yaml`、示例 jar 放进容器（CLI 不上传产物）；
4. 逐项断言；全部通过才打印最后一行 `SMOKE OK`，并删除容器；
   任何断言失败都会非零退出，且保留容器方便排查（`docker exec -it xac-smoke bash`）。

前置条件：`docker`、JDK（`java` / `javac` / `jar`，能编 `--release 8`）、Maven。

`smoke.ps1` 自带 `--release 8` 编译检查；脚本本身用 ASCII 写成，路径统一用嵌套
`Join-Path` 构造，不依赖 `\` 作分隔符。Windows PowerShell 5.1 已在本环境验证；
pwsh 7+ 与 Linux/macOS 路径未在此环境实际执行。

### e2e coverage

脚本里真正钉住的行为，每条都有独立断言：

- **path-links 状态机**（Windows 上 JUnit 会跳过这部分，这里是唯一执行点）：
  1. 目标不存在 → 建软链接，指向配置里的源；
  2. 再次 `apply` → 幂等：链接 inode 不变、不新增 `.bak`；
  3. 目标处是真实目录 → 移到 `<target>.bak`，再建链接；
  4. 目标处是指向别处的软链接 → 备份为 `.bak`；`.bak` 已存在时改用带时间戳的
     `<target>.bak.yyyyMMddHHmmss`，旧备份内容与旧链接目标都保留；
  5. `destroy` 只删指向配置源的链接：目标处被换成真实文件 / 外来软链接时原样保留。
- **status**：apply 前报漂移（退出码 1，逐条 `drift:`，含每个 `path-links[i]`）；
  apply 后 `(in sync)` + `auto-start: enabled`，退出码 0。
- **apply**：`systemctl enable / restart`、写 unit（`stat -c %a` 断言 0644）与 env
  （`stat -c %a` 断言 0600）、enable 后存在
  `multi-user.target.wants/demo.service` 软链且指向 unit、`EnvironmentFile` 注入
  `GREETING`、`WorkingDirectory` 等于包目录、`-Xmx256m` 生效（JVM 报 `XMX=268435456`）。
- **logs**：`xac logs` 能读到 journal 里的服务输出。
- **stop**：`systemctl stop` 后 `ActiveState=inactive`（`SuccessExitStatus=143`，不是
  failed），且 shutdown hook 打印 `STOPPING`。
- **destroy**：unit / env / `multi-user.target.wants/demo.service` 软链本身 / 自己创建的
  链接都不在了（软链用 `test -L` 断言，即使已悬空也必须消失），`LoadState=not-found`，
  且 `pgrep -f /opt/demo/app\.jar` 无残留进程；
  被备份的真实目录、既有 `.bak`、时间戳备份、真实文件、外来软链接全部保留。

所有 `/opt/demo`、`/opt/links/*`、`/opt/elsewhere` 都只在一次性容器里创建，
成功后随容器一起消失。
