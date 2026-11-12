# e2e 环境

跑真 systemd 的容器环境，用来验证 `xac apply / status / logs / destroy` 落到机器上的真实效果。
目标平台是 Ubuntu 24.04 + systemd 255（设计文档 D17）。

断言全部在 `src/test/java/io/xac/systemd/e2e/SmokeIT.java` 里，用 JUnit 5 + Testcontainers
驱动，不再有独立的 PowerShell 脚本。

## 构建镜像

在 `java-systemd-service-as-code/` 下执行：

```bash
docker build -t xac-e2e:24.04 e2e
```

镜像里是 `systemd systemd-sysv dbus procps openjdk-21-jre-headless`，`STOPSIGNAL SIGRTMIN+3`，
`CMD ["/sbin/init"]`。它只用于 e2e，不随 CLI 分发。

## 运行

```bash
# 在 java-systemd-service-as-code/ 下
mvn -Pe2e verify

# 或在仓库根
mvn -f java-systemd-service-as-code/pom.xml -Pe2e verify
```

`mvn test` 只跑单元/集成测试，**不会碰 Docker**；e2e 在 `e2e` profile 里由
`maven-failsafe-plugin` 绑定到 `integration-test` / `verify`，`SmokeIT` 带 `@Tag("e2e")`。
`-Pe2e` 会先 `package` 出 fat jar，再启动容器跑测试。Docker 不可用时它会直接失败，
不会静默跳过。

一次完整运行约 20 秒（容器内测试约 11 秒）。

## 容器怎么起

`SmokeIT` 用 `GenericContainer`，配置与手工复现一致：

- `--privileged`、`--cgroupns=host`（systemd 作为 PID 1 的前提）；
- `--tmpfs /run --tmpfs /run/lock --tmpfs /tmp`；
- **显式**把 entrypoint 覆写为 `/sbin/init` 并清空 CMD。本机缓存的 `xac-e2e:24.04`
  元数据是 `ENTRYPOINT ["/bin/sh"]`、`CMD ["-c", "sleep 3600"]`，不覆写会变成
  `/bin/sh /sbin/init`；从本仓库 Dockerfile 重新构建的镜像也同样适用。

systemd 起来后轮询 `systemctl is-system-running`，最多 60 秒，接受 `running` 或
`degraded`（容器里 getty 之类必然失败，degraded 是正常状态）。不用
`Wait.forLogMessage`：PID 1 的输出不是这里的启动契约。

## 被测产物怎么进去

测试在宿主机上：

1. 从 `target/` 里按 manifest 的 `Main-Class: io.xac.systemd.cli.Main` 找到 CLI fat jar
   （跳过 `original-*.jar` 与 `*-shaded.jar`），复制到容器 `/opt/xac.jar`；
2. 用测试类 `io.xac.systemd.e2e.DemoApp` 的 class 文件 + manifest 打一个可运行 jar，
   复制到 `/opt/demo/app.jar`；
3. 生成 `xac.yaml`，复制到 `/opt/demo/xac.yaml`。

只通过 `copyFileToContainer` 放入，CLI 不上传产物，与真实部署模型一致。

## 覆盖的场景

`SmokeIT` 按 `@Order` 顺序共享一个容器；`demo` 服务日志走 journald，`filelog` 服务日志由应用自己写文件：

1. **apply 前 status**：退出码 1，`unit (missing)`、`env (out of sync)`、逐条 `drift:`。
2. **path-links 护栏**：目标处是真实目录 / 外来软链接 / 普通文件 / 悬空链接时
   `apply` 退出码 2，报 `path-links[1..4]`，不写 unit/env、不启动进程、原内容不变、
   不产生 `.bak`。
3. **apply**：退出码 0；unit 0644、env 0600；`UnitFileState=enabled`、`ActiveState=active`、
   wants 软链指向 unit；`${GREETING}` 不切词、`%%`→`%`、`$$`→`$`、`-Xmx256m` 生效、
   `CWD` 等于 workdir；7 条链接指向 `/opt/demo/app.jar`；status 退出码 0 且无 drift；
   单元名/env 名始终是 `xac.demo.*`，不会出现 `demo.service`。
4. **--dry-run**：unit/env 的 inode 与 mtime、服务 PID 都不变，只打印 `would ...`。
5. **重复 apply**：链接 inode 不变、无 `.bak`，但服务确实被 restart（PID 变化）。
6. **logs**：能读到 `READY pid=`，`logs -n 5` 不超过 5 行（`--no-pager` 不挂起）。
7. **stop / start / status**：`stop` 后 `inactive`、`Result=success`、journal 有 `STOPPING`；
   此时 status 退出码 1；`start` 后恢复；改 env 文件后 status 退出码 1；重新 apply 后 0。
8. **destroy --yes**：停服、删 unit/env/wants、`LoadState=not-found`、进程消失、
   自己建的链接被删除；目标处换成真实文件与外来软链接时原样保留。
9. **shadow guard**：`/usr/lib/systemd/system/xac.shadow.service` 存在时，对 `name: shadow`
   的 apply 退出码 2，消息里出现 FragmentPath，且不写 `/etc/systemd/system/xac.shadow.service`
   与 env。
10. **logs.file**：`filelog` 服务用 `-Dlog.file=/var/log/filelog/app.log` 自己写日志。
    apply 后文件里出现 `FILELOG-READY`；`logs` 读到的是这个文件而不是 journald
    （输出里有 `FILELOG-`、没有 journal 才有的 `READY pid=`），`-n 2` 不超过 2 行；
    指向不存在文件的包退 2 并报 `log file not found: ...`；`destroy` 删 unit/env，
    但**不删**那个日志文件，内容仍在。

## 注意

- 容器 locale 是 `C`，CLI 的 `System.out` 按 ASCII 编码，中文错误信息会变成 `?`。
  因此断言中文消息时只匹配稳定的 ASCII 片段（如路径、`unit:`、`path-links[i]`），
  这不是放松检查：路径和退出码仍然逐项断言。
- Testcontainers 1.19.8 内置的 docker-java 在未指定 API 版本时会退回 1.32，Docker 29
  已把最低 API 提到 1.44，连接会被拒。`SmokeIT` 先问本机 `docker version` 的服务端
  API 版本并设置系统属性 `api.version`，兼容新旧 Docker；没有 docker CLI 时保持默认。
- 所有 `/opt/demo`、`/opt/filelog`、`/opt/badlog`、`/opt/links/*`、`/opt/elsewhere`、
  `/var/log/filelog` 都只在一次性容器里创建，成功后随容器一起消失。
