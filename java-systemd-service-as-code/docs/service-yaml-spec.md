# service.yaml 规范

本文档定义 `service.yaml` 的写法。它描述一个服务是什么、需要什么，不描述 systemd 怎么实现。

## 1. 概述

`service.yaml` 是一个服务的唯一真源。服务名、启动所需的 jar、JVM 参数、环境变量、工作目录、
软链接、启停行为，全部写在这一个文件里。

文件只承载"对服务的描述"。加固、日志去向、重启细节、unit 的结构由部署侧决定，不在本规范范围内
（见 §8）。

## 2. 位置与命名

- 放在服务包目录下，固定叫 `service.yaml`。
- 服务名只来自文件里的 `name` 字段。文件名、目录名都不参与。
- 编码必须是 UTF-8，顶层必须是一个映射（键值对），不能是列表。

## 3. 字段总览

| 字段 | 必填 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | 是 | 字符串 | — | 服务名 |
| `description` | 否 | 字符串 | 空 | 一句话说明 |
| `artifact` | 是 | 映射 | — | 见 §4.1 |
| `runtime` | 是 | 映射 | — | 见 §4.2 |
| `env` | 否 | 映射 | 空 | 注入进程环境的变量，见 §4.3 |
| `properties` | 否 | 映射 | 空 | 展开成 `-Dkey=value`，见 §4.4 |
| `workdir` | 否 | 字符串 | 包目录 | 进程的工作目录，见 §4.5 |
| `links` | 否 | 字符串列表 | 空 | 软链接表，见 §4.6 |
| `lifecycle` | 否 | 映射 | 见 §4.7 | 启停行为 |

未知字段一律视为错误，不会忽略——拼错的键如果被静默跳过，问题要到启动时才暴露。

## 4. 字段说明

### 4.1 artifact

| 子字段 | 必填 | 说明 |
|--------|------|------|
| `jar` | 是 | 主 jar 的路径，相对包目录或绝对路径 |

必须指向一个实际存在的文件。只支持单 jar 的 `-jar` 方式，不支持 classpath 与自写 `main`。

```yaml
artifact:
  jar: ../libs/mesunisystemservice-nonhis-basic-3.6.6.0.jar
```

### 4.2 runtime

| 子字段 | 必填 | 说明 |
|--------|------|------|
| `java` | 是 | java 可执行文件的绝对路径 |
| `opts` | 是 | JVM 参数列表 |

`opts` 的每一项是一个完整参数，按列表原样传递，**不按空格切分**，因此一项里可以带空格。
不要把 `-Xmx` 和 `2457m` 拆成两项——JVM 只认连写的 `-Xmx2457m`。

`opts` 里必须能解析出堆大小，即至少有一项形如 `-Xmx<数值><单位>`，或 `-XX:MaxRAMPercentage=`。
不写堆大小时视为错误：JVM 默认取物理内存的 1/4，漏写不会导致启动失败，只会静默地用错内存。

GC 日志与堆转储也在 `opts` 里指定。`-Xloggc:` 与 `-XX:HeapDumpPath=` 指向的目录如果不存在，
会在启动前被创建（JVM 自己不会建）。

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

注入进程环境的变量。

- 键名只能是字母、数字、下划线，且不能以数字开头。
- 值是字面量，**不做插值**。需要引用变量的地方是 `properties` 和 `runtime.opts`。
- 值里的 `%` 不需要转义，转义由部署侧处理。

```yaml
env:
  SPRING_PROFILES: uat
  APP_ID: com.huawei.supply.mes.mesplus.msm
  CONFIGCENTER_PWD: mes123
```

配置中心密码这类敏感值会原样落到部署产物里，所在文件以仅 root 可读的权限保存。

### 4.4 properties

展开成 java 的 `-Dkey=value` 参数。

- 值里可以用 `${名字}` 引用 `env` 里的键，引用不到视为错误。
- 要表示字面的 `${`，写成 `$${`。
- `runtime.opts` 里同样支持这种引用。

```yaml
env:
  SPRING_PROFILES: uat

properties:
  spring.profiles.active: ${SPRING_PROFILES}
  application.appId: com.huawei.supply.mes.mesplus.msm
```

展开结果：`-Dspring.profiles.active=uat -Dapplication.appId=com.huawei.supply.mes.mesplus.msm`

### 4.5 workdir

进程的工作目录，等价于启动前 `cd` 到这个目录。不写时默认用包目录。

- 可以写相对包目录的路径，也可以写绝对路径。
- 目录不存在时会在启动前被创建。
- 目录若在网络挂载上，开机时可能尚未就绪，服务会以 `Failed at step CHDIR` 失败。这类服务
  必须保证挂载先于服务完成。

### 4.6 links

软链接表，用来把应用写死的固定路径转到包内目录。每项是一个字符串 `源:目标`：

- 源：真实目录，相对包目录或绝对路径。
- 目标：应用看到的绝对路径。
- 按**最后一个冒号**切分，源路径里含冒号也能正确解析。

```yaml
links:
  - ./logs:/applog/messystemservice
  - ./appfile:/appfile/messystemservice
```

含义：`/applog/messystemservice` 指向包目录下的 `logs`，`/appfile/messystemservice` 指向包目录
下的 `appfile`。

目标位置如果已是真实目录（例如旧版本留下的），会先被备份成 `.bak`（已有 `.bak` 则加时间戳），
再建链接。删除服务时一并移除这些链接。

`links` 只建链接，没有别的副作用；它不影响工作目录，也不影响哪里可写。

### 4.7 lifecycle

| 子字段 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | 布尔 | `true` | 是否开机自启 |
| `restart` | 布尔 | `true` | 进程失败退出后是否自动拉起 |
| `stop-timeout` | 整数（秒） | `60` | 停止时等待进程退出的最长时间，超时强杀 |

`restart` 只有开和关：开等于"非正常退出就重启"，正常退出（退出码 0）不会被拉起。

## 5. 值的写法约定

- 路径：相对路径一律相对**包目录**写，不是相对当前工作目录。
- 字符串里的 `${...}`：只在 `properties` 与 `runtime.opts` 中作为变量引用处理。
- 布尔值写 `true` / `false`，不要写 `yes` / `no`。
- 数字不加引号。

## 6. 校验规则

一份合法的 `service.yaml` 必须同时满足：

1. 顶层是映射；`name`、`artifact.jar`、`runtime.java`、`runtime.opts` 齐备。
2. 没有未知字段，也没有类型不符的字段（如 `opts` 写成字符串而非列表）。
3. `name` 只含字母、数字、`-`、`_`、`.`、`:`，不含 `/`，不以 `.` 开头。
4. `artifact.jar` 指向的文件存在。
5. `runtime.java` 指向的文件存在且可执行。
6. `runtime.opts` 中能解析出堆大小。
7. `properties`、`runtime.opts` 里的 `${...}` 都能在 `env` 中找到。
8. `env` 的键名合法；`links` 的每一项都能按最后一个冒号切成非空的两段，且目标以 `/` 开头。

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

links:
  - ./logs:/applog/messystemservice
  - ./appfile:/appfile/messystemservice

lifecycle:
  enabled: true
  restart: true
  stop-timeout: 60
```

## 8. 本规范不覆盖的内容

以下由部署侧决定，不在这份文件里配置：

- systemd 的加固项与进程属性
- 重启间隔、启动失败次数上限、停止方式
- 日志去向与标识
- unit 文件的位置与命名
