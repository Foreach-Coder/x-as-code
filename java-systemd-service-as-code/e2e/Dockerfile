FROM ubuntu:24.04

# systemd 是 PID 1；其余是 systemctl / journalctl 依赖的最小集合。
# JRE 用 21：xac 的 jar 以 --release 8 编译，在 21 上运行（见设计文档 D4）。
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      systemd systemd-sysv dbus procps \
      openjdk-21-jre-headless \
 && rm -rf /var/lib/apt/lists/*

# 让 docker stop 走 systemd 的正常关机流程，而不是直接 SIGKILL。
STOPSIGNAL SIGRTMIN+3

CMD ["/sbin/init"]
