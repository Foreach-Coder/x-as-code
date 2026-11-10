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
