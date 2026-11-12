# xac.yaml 规范（v1）

本文档定义 `xac.yaml` 的写法：一个服务的唯一描述文件。

## 1. 概述

`xac.yaml` 描述一个跑在 systemd 上的 Java 服务：叫什么、跑哪个 jar、用什么 JVM、传什么参数、
注入什么环境变量、工作目录在哪、有哪些固定路径要映射、以什么策略启停。

这份 DSL 就是为 systemd 写的，不假装可移植。`lifecycle` 段是工具自己的抽象层：键名尽量贴合
systemd 的说法，但不做指令直通表——systemd 有几百条指令，全量直通既不可行也没必要。

## 2. 位置与命名

- 每个服务一个**包目录**，`xac.yaml` 固定放在包目录下，文件名固定为 `xac.yaml`。
- 所有相对路径一律相对**包目录**解析，不是相对当前工作目录。
- 服务名只来自文件里的 `name` 字段；文件名与目录名都不参与命名。
- 生成的 unit / env 文件名统一加 `xac.` 前缀（`xac.<name>.service`、`xac.<name>.env`），
  与系统 unit / env 分属不同命名空间；`name` 本身不要带这个前缀。
- 编码必须是 UTF-8，顶层必须是一个映射（键值对）。

## 3. 字段总览

| 字段 | 必填 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | 是 | 字符串 | — | 服务名；生成的 unit / env 名为 `xac.<name>.service` / `xac.<name>.env` |
| `description` | 否 | 字符串 | 空 | 一句话说明 |
| `artifact` | 是 | 映射 | — | 见 §4.1 |
| `runtime` | 是 | 映射 | — | 见 §4.2 |
| `env` | 否 | 映射 | 空 | 注入进程环境的变量，见 §4.3 |
| `properties` | 否 | 映射 | 空 | 展开成 `-Dkey=value`，见 §4.4 |
| `workdir` | 否 | 字符串 | 包目录 | 进程的工作目录，见 §4.5 |
| `path-links` | 否 | 字符串列表 | 空 | 路径链接表，见 §4.6 |
| `logs` | 否 | 映射 | journald | `logs` 命令的日志来源，见 §4.8 |
| `lifecycle` | 否 | 映射 | 见 §4.7 | 自启与启停，见 §4.7 |

未知字段一律视为错误，不会忽略——拼错的键如果被静默跳过，问题要到服务启动时才暴露。

## 4. 字段说明

### 4.1 artifact

| 子字段 | 必填 | 说明 |
|--------|------|------|
| `jar` | 是 | 主 jar 的路径，相对包目录或绝对路径 |

必须指向一个实际存在的文件。只支持单 jar 的 `-jar` 启动方式，不支持 classpath、不支持自写 `main`。

```yaml
artifact:
  jar: ../libs/mesunisystemservice-nonhis-basic-3.6.6.0.jar
```

### 4.2 runtime

| 子字段 | 必填 | 说明 |
|--------|------|------|
| `java` | 是 | java 可执行文件的绝对路径 |
| `opts` | 否 | JVM 参数列表，省略时为空列表 |

`opts` 的每一项是一个完整参数，按列表原样传递，**不按空格切分**，所以一项里可以带空格。
不要把 `-Xmx` 和 `2457m` 拆成两项——JVM 只认连写的 `-Xmx2457m`。

`opts` 的内容**不做任何解析**：不从中推导目录、不检查有没有堆大小。写什么就传什么。
（要不要写 `-Xmx`、GC 日志目录谁来建，是使用者自己的事。）

```yaml
runtime:
  java: /usr/local/jdk8/bin/java
  opts:
    - -Xmx2457m
    - -XX:MaxMetaspaceSize=512m
    - -XX:+HeapDumpOnOutOfMemoryError
    - -XX:HeapDumpPath=/applog/messystemservice/heapdump
    - -Xloggc:/applog/messystemservice/gc/gc.log
    - -XX:+UseG1GC
```

### 4.3 env

注入进程环境的变量。最终落到一个仅 root 可读的环境变量文件里。

- 键名只能是字母、数字、下划线，且不能以数字开头。
- 值是字面量，**不做插值**。需要引用变量的地方是 `properties` 和 `runtime.opts`。
- 值里的 `%` 不需要转义。
- 值里不能有换行。

```yaml
env:
  SPRING_PROFILES: uat
  APP_ID: com.huawei.supply.mes.mesplus.msm
  CONFIGCENTER_PWD: mes123
```

### 4.4 properties

展开成 java 的 `-Dkey=value` 参数。

- 值里可以写 `${名字}` 引用 `env` 里的键。
- 这个引用**不在生成 unit 时展开**：原样写进 unit，由 systemd 在服务启动时用环境变量展开。
  所以密码只存在于仅 root 可读的环境变量文件里，不会出现在 unit 文件里。
- `runtime.opts` 里同样支持这种引用。
- 要表示字面的 `$`，写成 `$$`。
- `${名字}` 在 `env` 里找不到时视为错误——否则启动时会被静默展开成空串。

```yaml
env:
  SPRING_PROFILES: uat

properties:
  spring.profiles.active: ${SPRING_PROFILES}
  application.appId: com.huawei.supply.mes.mesplus.msm
  configcenter.password: ${CONFIGCENTER_PWD}
```

写进 unit 的是 `-Dspring.profiles.active=${SPRING_PROFILES} -Dconfigcenter.password=${CONFIGCENTER_PWD}`，
服务启动时由 systemd 换成实际值。

### 4.5 workdir

进程的工作目录，等价于启动前 `cd` 到这个目录。不写时默认用包目录。

- 可以写相对包目录的路径，也可以写绝对路径。
- 目录不存在时会在启动前被创建。
- 目录若在网络挂载上，开机时可能尚未就绪，服务会以 `Failed at step CHDIR` 失败。这类服务
  必须保证挂载先于服务完成。

### 4.6 path-links

路径链接表，把应用写死的固定路径转到包内目录。每项是一个字符串 `源:目标`：

- 源：真实目录，相对包目录或绝对路径。
- 目标：应用看到的绝对路径。
- 按**最后一个冒号**切分，源路径里含冒号也能正确解析。

```yaml
path-links:
  - ./logs:/applog/messystemservice
  - ./appfile:/appfile/messystemservice
```

含义：`/applog/messystemservice` 指向包目录下的 `logs`，`/appfile/messystemservice` 指向包目录
下的 `appfile`。

目标的三种情形：目标不存在 → 建链接；目标已是指向该源的软链接 → 跳过；其余任何形态（真实目录、
普通文件、指向别处的软链接、悬空软链接、FIFO/socket 等）→ **整次 `apply` 直接报错退 2**，不移动、
不删除、不覆盖任何东西，交给人处理。报错信息为
`目标已存在且不是指向源的软链接，请人工处理: <目标>`。工具不会再把既有目录/文件备份成 `.bak`。

`destroy` 只删除「仍是软链接且指向配置的源」的那些链接；真实目录、普通文件与外来软链接都不碰。

**安全约束**（写错了就是把系统目录搬走，所以这些是硬错误）：

- 目标不能是 `/`，不能落在 `/etc`、`/usr`、`/bin`、`/sbin`、`/lib*`、`/boot`、`/var`、`/root`、
  `/home`、`/proc`、`/sys`、`/dev`、`/run` 及其子孙之下
- 目标不能等于或包住包目录、unit 目录、env 文件
- 目标不能等于源，也不能是源的上级目录
- 源必须存在（只在 `apply` 检查；`status`、`logs`、`destroy` 在产物已删时也能用）

判定会先解析真实路径，预置的符号链接不能用来绕过。

`path-links` 只建链接，不影响工作目录，也不影响哪里可写。

### 4.7 lifecycle

这一段是工具自己的抽象，不是 systemd 指令的直通表：键名尽量贴合 systemd 的说法，但不追求逐条对应。
systemd 的指令有几百条，全部直通既不可行也没必要；需要放开哪些，按实际需求一条条加。

| 子字段 | 类型 | 默认值 | 对应 systemd 指令 |
|--------|------|--------|------------------|
| `auto-start` | 布尔 | `true` | `[Install] WantedBy=multi-user.target` |
| `restart` | 字符串 | `on-failure` | `Restart=` |
| `restart-sec` | 整数（秒） | `5` | `RestartSec=` |
| `timeout-stop-sec` | 整数（秒） | `60` | `TimeoutStopSec=` |

`auto-start` 决定开机时是否自动拉起服务，也就是 `systemctl enable` 的效果。`true` 时 unit 里写
`[Install] WantedBy=multi-user.target`；`false` 时**整段 `[Install]` 都不写**，`apply` 执行
`systemctl disable`。

`restart` 取 systemd 的原值：`no`、`on-success`、`on-failure`、`on-abnormal`、`on-abort`、
`on-watchdog`、`always`。默认 `on-failure`：只有非 0 退出才拉起，正常退出（退出码 0）不拉。

`restart-sec` 是进程退出后等待多久再拉起。systemd 自带的默认是 100ms，对 Java 服务太急，
这里默认 5 秒。

`timeout-stop-sec` 是停止时等待进程退出的最长时间，超时后 systemd 发 `SIGKILL`。

```yaml
lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 5
  timeout-stop-sec: 60
```

### 4.8 logs

`logs` 命令读哪里的日志。不写这一段时读 journald（`journalctl -u xac.<name>`）；写了就读指定文件，
适合那些不往控制台打日志、自己写日志文件的 Java 程序。

| 子字段 | 必填 | 说明 |
|--------|------|------|
| `file` | 是 | 日志文件的绝对路径 |

- 路径必须是绝对路径。相对路径会相对进程的 `workdir` 解析，而 `workdir` 在不同机器上可能不同。
- 只改变 `logs` 的读取来源；**不改变服务的日志去向**——unit 里仍然只设 `SyslogIdentifier`，
  文件由服务自己写、自己轮转。
- 文件不存在或不可读时，`logs` 报错并退 2。
- `-n` 与 `-f` 对文件模式同样有效：`-n` 从文件尾部取最后 N 行，`-f` 轮询追加；文件变短视为被截断，
  从头重读。v1 不跟随轮转后的新文件（见 §8）。

```yaml
logs:
  file: /var/log/messystemservice/app.log
```

## 5. 值的写法约定

- 路径：相对路径一律相对**包目录**写。
- 字符串里的 `${...}`：只在 `properties` 与 `runtime.opts` 中作为变量引用，且由 systemd 在启动时
  展开，本工具不展开。
- 字面的 `$` 写 `$$`。
- 布尔值写 `true` / `false`，不要写 `yes` / `no`。
- 数字不加引号。

## 6. 校验规则

一份合法的 `xac.yaml` 必须同时满足：

1. 顶层是映射；`name`、`artifact.jar`、`runtime.java` 齐备。
2. 没有未知字段，也没有类型不符的字段（如 `opts` 写成字符串而非列表）。
3. `name` 只含字母、数字、`-`、`_`、`.`、`:`，不含 `/`，不以 `.` 开头，且不以 `xac.` 开头
   （不区分大小写；工具会自动加该前缀）。
4. `env` 的键名合法、值不含换行、键不重复。
5. `path-links` 的每一项都能按最后一个冒号切成非空的两段，且目标以 `/` 开头。
6. `path-links` 的目标不落在受保护位置，也不等于或包住包目录、unit 目录、env 文件（见 §4.6）。
7. `path-links` 的目标既不能等于源，也不能是源的上级目录。
8. `properties`、`runtime.opts` 里的 `${...}` 都能在 `env` 中找到。
9. `lifecycle.auto-start` 是布尔值；`lifecycle.restart` 是合法的 systemd 取值；
   `restart-sec`、`timeout-stop-sec` 是正整数。
10. 会写进 unit 的字符串不含换行：`description`、`artifact.jar`、`runtime.java`、`workdir`、
    `runtime.opts` 的每一项、`properties` 的键与值。
11. `logs.file` 是绝对路径（见 §4.8）。

以下三条需要在本机看文件，由 CLI 在动手前检查：

12. `artifact.jar` 指向的文件存在。
13. `runtime.java` 指向的文件存在且可执行。
14. `path-links` 的源存在，且目标要么不存在、要么已是指向源的软链接——只有 `apply` 检查，
    `status`、`logs`、`destroy` 不需要产物还在。

## 7. 样例

### 7.1 最小可用

```yaml
name: demo
artifact:
  jar: demo.jar
runtime:
  java: /usr/local/jdk8/bin/java
  opts:
    - -Xmx512m
```

### 7.2 完整

```yaml
name: messystemservice
description: MES 主体服务

artifact:
  jar: ../libs/mesunisystemservice-nonhis-basic-3.6.6.0.jar

runtime:
  java: /usr/local/jdk8/bin/java
  opts:
    - -Xmx2457m
    - -XX:MaxMetaspaceSize=512m
    - -XX:+HeapDumpOnOutOfMemoryError
    - -XX:HeapDumpPath=/applog/messystemservice/heapdump
    - -Xloggc:/applog/messystemservice/gc/gc.log
    - -XX:+UseG1GC

workdir: /appfile/messystemservice

env:
  SPRING_PROFILES: uat
  APP_ID: com.xxx.msm
  APP_SUB_ID: messystemservice
  APP_NAME: messystemservice
  CONFIGCENTER_ADDR: 10.243.250.135:8848
  CONFIGCENTER_NS: dev_new
  CONFIGCENTER_USER: nacos
  CONFIGCENTER_PWD: mes123

properties:
  spring.profiles.active: ${SPRING_PROFILES}
  application.appId: ${APP_ID}
  application.subAppId: ${APP_SUB_ID}
  application.appName: ${APP_NAME}
  configcenter.url: ${CONFIGCENTER_ADDR}
  configcenter.namespace: ${CONFIGCENTER_NS}
  configcenter.username: ${CONFIGCENTER_USER}
  configcenter.password: ${CONFIGCENTER_PWD}

path-links:
  - ./logs:/applog/messystemservice
  - ./appfile:/appfile/messystemservice

lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 5
  timeout-stop-sec: 60
```

## 8. 本规范不覆盖的内容

以下由工具内置策略决定，不在文件里配置：

- systemd 加固项与进程属性（`User=`、`PrivateTmp=` 等）
- 服务的日志去向与标识（标识符 = `xac.<name>`；写 stdout 进 journald 还是自己写文件，由服务决定；
  `logs` 读哪里由 §4.8 决定）
- 日志文件的创建、权限与轮转（文件由服务自己管理；`logs -f` 不跟随轮转后的新文件）
- unit 文件的位置与命名（`/etc/systemd/system/xac.<name>.service`）
- 环境变量文件的位置与权限（`/etc/xac/xac.<name>.env`，`0600`）
