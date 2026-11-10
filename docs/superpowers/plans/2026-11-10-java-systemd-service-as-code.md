# java-systemd-service-as-code v1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 `xac` CLI 的第一个子项目：读取包目录下的 `xac.yaml`，把 Java 服务安装成 systemd unit，并提供 `apply` / `status` / `logs` / `destroy` 四个命令。

**Architecture:** 单 Maven 模块，五个包单向依赖：`cli`（picocli）→ `apply`（固定顺序的 `List<Action>`）→ `systemd`（unit 渲染）与 `exec`（唯一调用外部进程处）；`spec` 负责 SnakeYAML → schema → 模型。所有副作用都经 `CommandRunner` 或可注入的目录参数，测试用临时目录 + `RecordingRunner`，不依赖真实 systemd。

**Tech Stack:** Java 21 编译（`--release 8`）、Maven、picocli 4.7.6、SnakeYAML 2.2、JUnit 5.10.2、maven-shade-plugin 打 fat jar。

**Spec:** `docs/superpowers/specs/2026-11-10-java-systemd-service-as-code-design.md`（下称「设计文档」）与 `java-systemd-service-as-code/docs/xac-yaml-spec.md`（下称「规范」）。

## Global Constraints

- 编译：`maven.compiler.release=8`。**禁止** `record`、`var`、`switch` 表达式、文本块、`List.of`/`Map.of`、`Optional.isEmpty()`、`ProcessHandle`、`Stream.toList()`。
- 依赖只用：picocli 4.7.6、SnakeYAML 2.2、JUnit Jupiter 5.10.2。**不新增依赖**（不需要 Jackson、不需要日志框架）。
- 外部命令一律 `ProcessBuilder` + `List<String>` argv，**禁止**拼接 shell 字符串、禁止 `/bin/sh -c`。
- 路径常量：unit 目录 `/etc/systemd/system`，env 目录 `/etc/xac`，env 文件名 `<name>.env`，unit 文件名 `<name>.service`。两个目录都必须可注入（测试用临时目录）。
- 退出码：`0` 成功；`1` 执行期失败；`2` 输入错误且未改动任何东西（设计文档 §6.6）。
- 面向用户的输出：标签用英文（与设计文档示例一致），提示与错误文案用中文。
- 渲染必须确定：不含时间戳/主机名/随机数，map 按 yaml 原始顺序，输出以单个 `\n` 结尾。
- 每个 Task 结束必须 `mvn -q test` 全绿并提交。

## 文件结构

```
java-systemd-service-as-code/
  pom.xml
  src/main/java/io/xac/systemd/
    cli/Main.java                     fat jar 入口
    cli/RootCommand.java              xac 根命令，装配子命令与退出码
    cli/ApplyCommand.java             apply [<package-dir>] [--dry-run]
    cli/StatusCommand.java            status [<package-dir>]
    cli/LogsCommand.java              logs [<package-dir>] [-n N] [-f]
    cli/DestroyCommand.java           destroy [<package-dir>] [--dry-run] [--yes]
    cli/CommandContext.java           解析包目录、加载 spec、预检、构造 ApplyContext
    exec/ExecResult.java              退出码 + stdout + stderr
    exec/CommandRunner.java           接口：跑命令 / 直通 IO
    exec/ProcessRunner.java           真实实现
    exec/RecordingRunner.java         测试替身，记录 argv、可预置结果
    spec/Violation.java               字段路径 + 消息
    spec/Validator.java               一条规则一个实现
    spec/ServiceSpec.java             模型 + Builder（字段可空）
    spec/Lifecycle.java               启停参数（可空，带缺省方法）
    spec/PathLink.java                "源:目标" 解析
    spec/Schema.java                  字段表：类型、必填、未知字段判定
    spec/Yaml12Resolver.java          SnakeYAML 的 YAML 1.2 布尔解析（避免 no/on 变布尔）
    spec/SpecLoader.java              yaml → 结构校验 → ServiceSpec
    spec/ValidationRunner.java        跑 List<Validator>，收集全部违规
    spec/validators/NameValidator.java
    spec/validators/EnvValidator.java
    spec/validators/PathLinksValidator.java
    spec/validators/PlaceholderValidator.java
    spec/validators/LifecycleValidator.java
    systemd/UnitNaming.java           unit/env 文件名与路径
    systemd/SystemdTarget.java        unit 目录、env 目录、包目录
    systemd/Directive.java            unit 指令：spec → 一行或 null
    systemd/UnitRenderer.java         渲染 unit 与 env 文件（纯函数）
    systemd/SystemctlShow.java        解析 systemctl show 输出
    apply/Action.java                 describe + run
    apply/ActionFailure.java          带命令与退出码的失败
    apply/ApplyContext.java           一次 apply 的全部输入
    apply/ApplyRunner.java            按顺序执行 7 个动作，支持 dry-run
    apply/DestroyRunner.java         按顺序执行 6 个动作
    apply/FileWriter.java            临时文件 + 权限 + 原子替换
    apply/EnvironmentProbe.java      是否 root / systemd 是否在跑 / 文件存在且可执行
    apply/RealEnvironmentProbe.java
    apply/Preflight.java             输入与环境检查
    apply/actions/PathLinksAction.java
    apply/actions/WorkdirAction.java
    apply/actions/EnvFileAction.java
    apply/actions/UnitFileAction.java
    apply/actions/DaemonReloadAction.java
    apply/actions/AutoStartAction.java
    apply/actions/RestartAction.java
    apply/actions/RemoveUnitAction.java
    apply/actions/RemoveEnvAction.java
    apply/actions/RemoveLinksAction.java
    apply/actions/StopAction.java
    apply/actions/DisableAction.java
    status/StatusRunner.java          读 unit/env 文件 + systemctl show，输出报告
    status/StatusResult.java          报告数据
    logs/LogsRunner.java              journalctl
  src/test/java/io/xac/systemd/...    与上面一一对应
```

依赖方向：`cli → apply → (systemd, exec)`, `apply → spec`, `systemd → spec`, `spec` 不认识其它包。

---

### Task 1: Maven 骨架与 exec 层

**Files:**
- Create: `java-systemd-service-as-code/pom.xml`
- Create: `java-systemd-service-as-code/src/main/java/io/xac/systemd/exec/ExecResult.java`
- Create: `java-systemd-service-as-code/src/main/java/io/xac/systemd/exec/CommandRunner.java`
- Create: `java-systemd-service-as-code/src/main/java/io/xac/systemd/exec/ProcessRunner.java`
- Create: `java-systemd-service-as-code/src/main/java/io/xac/systemd/exec/RecordingRunner.java`
- Test: `java-systemd-service-as-code/src/test/java/io/xac/systemd/exec/ProcessRunnerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `new ExecResult(int exitCode, String stdout, String stderr)`；`boolean ok()`
  - `interface CommandRunner { ExecResult run(List<String> argv); void runInheritIo(List<String> argv); }`
  - `class ProcessRunner implements CommandRunner`（无参构造）
  - `class RecordingRunner implements CommandRunner`：`List<List<String>> calls()`、`void addResult(List<String> argv, ExecResult result)`、`void addResultForCommand(String exe, ExecResult result)`

- [ ] **Step 1: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.xac</groupId>
  <artifactId>java-systemd-service-cli</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>8</maven.compiler.release>
  </properties>

  <dependencies>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>4.7.6</version>
    </dependency>
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
      <version>2.2</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>io.xac.systemd.cli.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 写失败测试**

`ProcessRunnerTest`（用 `java.home` 里的 `java`，Windows 与 CI 都能跑）：

```java
package io.xac.systemd.exec;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    private static String javaExe() {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        return java.toString();
    }

    @Test
    void runsCommandAndCapturesStderr() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(javaExe(), "-version"));
        assertTrue(result.ok(), "exit=" + result.exitCode);
        assertTrue(result.stderr.contains("version"), result.stderr);
    }

    @Test
    void reportsNonZeroExitCode() {
        CommandRunner runner = new ProcessRunner();
        ExecResult result = runner.run(Arrays.asList(javaExe(), "-XX:+NoSuchFlagForXac"));
        assertFalse(result.ok());
        assertTrue(result.exitCode > 0);
    }

    @Test
    void recordingRunnerRecordsArgvAndCannedResults() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, "", ""));
        runner.run(Arrays.asList("systemctl", "daemon-reload"));
        assertEquals(1, runner.calls().size());
        assertEquals(Arrays.asList("systemctl", "daemon-reload"), runner.calls().get(0));
    }
}
```

- [ ] **Step 3: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test`
Expected: `COMPILATION ERROR`：找不到 `ExecResult` / `CommandRunner` / `ProcessRunner` / `RecordingRunner`。

- [ ] **Step 4: 实现 exec 层**

`ExecResult.java`：

```java
package io.xac.systemd.exec;

public final class ExecResult {

    public final int exitCode;
    public final String stdout;
    public final String stderr;

    public ExecResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public boolean ok() {
        return exitCode == 0;
    }
}
```

`CommandRunner.java`：

```java
package io.xac.systemd.exec;

import java.util.List;

public interface CommandRunner {

    ExecResult run(List<String> argv);

    void runInheritIo(List<String> argv);
}
```

`ProcessRunner.java`：

```java
package io.xac.systemd.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProcessRunner implements CommandRunner {

    @Override
    public ExecResult run(List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        try {
            Process process = builder.start();
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            int exitCode = process.waitFor();
            return new ExecResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            return new ExecResult(127, "", "无法执行 " + argv.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(130, "", "被中断: " + argv.get(0));
        }
    }

    @Override
    public void runInheritIo(List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.inheritIO();
        try {
            builder.start().waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 " + argv.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

`RecordingRunner.java`（测试替身放 src/main 是因为后续 Task 的测试都要用它；也可以移到 test 目录，但设计文档 §3 把它列为 exec 包成员）：

```java
package io.xac.systemd.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecordingRunner implements CommandRunner {

    private final List<List<String>> calls = new ArrayList<List<String>>();
    private final Map<String, ExecResult> byCommand = new HashMap<String, ExecResult>();
    private final List<List<String>> inheritIo = new ArrayList<List<String>>();

    public void addResultForCommand(String executable, ExecResult result) {
        byCommand.put(executable, result);
    }

    public List<List<String>> calls() {
        return calls;
    }

    public List<List<String>> inheritIoCalls() {
        return inheritIo;
    }

    @Override
    public ExecResult run(List<String> argv) {
        calls.add(new ArrayList<String>(argv));
        ExecResult result = byCommand.get(argv.get(0));
        return result == null ? new ExecResult(0, "", "") : result;
    }

    @Override
    public void runInheritIo(List<String> argv) {
        inheritIo.add(new ArrayList<String>(argv));
        calls.add(new ArrayList<String>(argv));
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: 确认字节码是 Java 8**

Run（Windows PowerShell）：
```powershell
javap -verbose java-systemd-service-as-code/target/classes/io/xac/systemd/exec/ExecResult.class | Select-String "major"
```
Expected: `major version: 52`

- [ ] **Step 7: 提交**

```bash
git add java-systemd-service-as-code/pom.xml java-systemd-service-as-code/src
git commit -m "build: maven 骨架与 exec 层"
```

---

### Task 2: spec 模型、Schema 与 yaml 加载

**Files:**
- Create: `src/main/java/io/xac/systemd/spec/Violation.java`
- Create: `src/main/java/io/xac/systemd/spec/Lifecycle.java`
- Create: `src/main/java/io/xac/systemd/spec/PathLink.java`
- Create: `src/main/java/io/xac/systemd/spec/ServiceSpec.java`
- Create: `src/main/java/io/xac/systemd/spec/Schema.java`
- Create: `src/main/java/io/xac/systemd/spec/Yaml12Resolver.java`
- Create: `src/main/java/io/xac/systemd/spec/SpecLoader.java`
- Test: `src/test/java/io/xac/systemd/spec/SpecLoaderTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `new Violation(String path, String message)`；字段 `path`、`message`；`String toString()` → `path + ": " + message`
  - `ServiceSpec.Builder`：`name(String)`、`description(String)`、`jar(String)`、`java(String)`、`opts(List<String>)`、`env(Map<String,String>)`、`properties(Map<String,String>)`、`workdir(String)`、`pathLinks(List<String>)`、`lifecycle(Lifecycle)`、`packageDir(Path)`、`build()`
  - `ServiceSpec` public final 字段：`name`、`description`、`jar`（`Path`，包目录下解析后绝对路径）、`java`（`String`）、`opts`（`List<String>`）、`env`（`Map<String,String>`，保持 yaml 顺序）、`properties`（同）、`workdir`（`Path` 可空）、`pathLinks`（`List<String>` 原文）、`lifecycle`、`packageDir`
  - `Lifecycle`：字段 `autoStart`（`Boolean` 可空）、`restart`（`String` 可空）、`restartSec`（`Integer` 可空）、`timeoutStopSec`（`Integer` 可空）；方法 `boolean autoStartOrDefault()`（true）、`String restartOrDefault()`（`"on-failure"`）、`int restartSecOrDefault()`（5）、`int timeoutStopSecOrDefault()`（60）
  - `PathLink.parse(String raw)` → `PathLink` 或 `null`；字段 `source`（`Path`）、`target`（`Path`）
  - `SpecLoader.load(Path packageDir)` → `SpecLoader.Result`：字段 `ServiceSpec spec`（有违规时为 null）、`List<Violation> violations`

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecLoaderTest {

    @TempDir
    Path pkg;

    private Path write(String yaml) throws IOException {
        Path file = pkg.resolve("xac.yaml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String minimal() {
        return String.join("\n",
                "name: demo",
                "artifact:",
                "  jar: app.jar",
                "runtime:",
                "  java: /usr/bin/java",
                "  opts:",
                "    - -Xmx512m",
                "");
    }

    @Test
    void loadsMinimalSpec() throws IOException {
        write(minimal());
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertTrue(result.violations.isEmpty(), result.violations.toString());
        assertNotNull(result.spec);
        assertEquals("demo", result.spec.name);
        assertEquals(pkg.resolve("app.jar").toAbsolutePath().normalize(), result.spec.jar);
        assertEquals(1, result.spec.opts.size());
        assertEquals("-Xmx512m", result.spec.opts.get(0));
        assertEquals("on-failure", result.spec.lifecycle.restartOrDefault());
        assertEquals(5, result.spec.lifecycle.restartSecOrDefault());
        assertEquals(60, result.spec.lifecycle.timeoutStopSecOrDefault());
        assertTrue(result.spec.lifecycle.autoStartOrDefault());
    }

    @Test
    void rejectsUnknownFieldWithPath() throws IOException {
        write(minimal().replace("name: demo", "name: demo\nnarration: 说明"));
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertEquals("narration: 未知字段", result.violations.get(0).toString());
    }

    @Test
    void rejectsWrongTypeWithPath() throws IOException {
        write(minimal().replace("    - -Xmx512m", "  opts: -Xmx512m"));
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.get(0).toString().startsWith("runtime.opts"),
                result.violations.toString());
    }

    @Test
    void reportsMissingRequiredFields() throws IOException {
        write("name: demo\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.toString().contains("artifact"));
        assertTrue(result.violations.toString().contains("runtime"));
    }

    @Test
    void keepsYaml12TextForRestartNo() throws IOException {
        write(minimal() + "lifecycle:\n  restart: no\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertTrue(result.violations.isEmpty(), result.violations.toString());
        assertEquals("no", result.spec.lifecycle.restart);
    }

    @Test
    void resolvesWorkdirRelativeToPackageDir() throws IOException {
        write(minimal() + "workdir: run\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertEquals(pkg.resolve("run").toAbsolutePath().normalize(), result.spec.workdir);
    }

    @Test
    void rejectsDuplicateEnvKey() throws IOException {
        write(minimal() + "env:\n  A: 1\n  A: 2\n");
        SpecLoader.Result result = SpecLoader.load(pkg);
        assertNull(result.spec);
        assertTrue(result.violations.toString().contains("A"), result.violations.toString());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=SpecLoaderTest`
Expected: `COMPILATION ERROR`：找不到 `SpecLoader`。

- [ ] **Step 3: 实现模型与加载器**

`Violation.java`：

```java
package io.xac.systemd.spec;

public final class Violation {

    public final String path;
    public final String message;

    public Violation(String path, String message) {
        this.path = path;
        this.message = message;
    }

    @Override
    public String toString() {
        return path.isEmpty() ? message : path + ": " + message;
    }
}
```

`Lifecycle.java`：

```java
package io.xac.systemd.spec;

public final class Lifecycle {

    public final Boolean autoStart;
    public final String restart;
    public final Integer restartSec;
    public final Integer timeoutStopSec;

    public Lifecycle(Boolean autoStart, String restart, Integer restartSec, Integer timeoutStopSec) {
        this.autoStart = autoStart;
        this.restart = restart;
        this.restartSec = restartSec;
        this.timeoutStopSec = timeoutStopSec;
    }

    public boolean autoStartOrDefault() {
        return autoStart == null || autoStart;
    }

    public String restartOrDefault() {
        return restart == null ? "on-failure" : restart;
    }

    public int restartSecOrDefault() {
        return restartSec == null ? 5 : restartSec;
    }

    public int timeoutStopSecOrDefault() {
        return timeoutStopSec == null ? 60 : timeoutStopSec;
    }
}
```

`PathLink.java`：

```java
package io.xac.systemd.spec;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathLink {

    public final String raw;
    public final Path source;
    public final Path target;

    private PathLink(String raw, Path source, Path target) {
        this.raw = raw;
        this.source = source;
        this.target = target;
    }

    /** 按最后一个冒号切分；不合法时返回 null。 */
    public static PathLink parse(String raw, Path packageDir) {
        if (raw == null) {
            return null;
        }
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            return null;
        }
        String source = raw.substring(0, colon);
        String target = raw.substring(colon + 1);
        if (target.isEmpty() || !target.startsWith("/")) {
            return null;
        }
        return new PathLink(raw, packageDir.resolve(source).normalize(), Paths.get(target));
    }
}
```

`ServiceSpec.java`：

```java
package io.xac.systemd.spec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceSpec {

    public final Path packageDir;
    public final String name;
    public final String description;
    public final Path jar;
    public final String java;
    public final List<String> opts;
    public final Map<String, String> env;
    public final Map<String, String> properties;
    public final Path workdir;
    public final List<String> pathLinks;
    public final Lifecycle lifecycle;

    private ServiceSpec(Builder b) {
        this.packageDir = b.packageDir;
        this.name = b.name;
        this.description = b.description;
        this.jar = b.jar == null ? null : b.packageDir.resolve(b.jar).normalize();
        this.java = b.java;
        this.opts = b.opts;
        this.env = b.env;
        this.properties = b.properties;
        this.workdir = b.workdir == null ? null : b.packageDir.resolve(b.workdir).normalize();
        this.pathLinks = b.pathLinks;
        this.lifecycle = b.lifecycle;
    }

    public static Builder builder(Path packageDir) {
        return new Builder(packageDir);
    }

    public static final class Builder {
        private final Path packageDir;
        private String name;
        private String description;
        private String jar;
        private String java;
        private List<String> opts = new ArrayList<String>();
        private Map<String, String> env = new LinkedHashMap<String, String>();
        private Map<String, String> properties = new LinkedHashMap<String, String>();
        private String workdir;
        private List<String> pathLinks = new ArrayList<String>();
        private Lifecycle lifecycle = new Lifecycle(null, null, null, null);

        private Builder(Path packageDir) {
            this.packageDir = packageDir;
        }

        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder jar(String v) { this.jar = v; return this; }
        public Builder java(String v) { this.java = v; return this; }
        public Builder opts(List<String> v) { this.opts = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v; return this; }
        public Builder properties(Map<String, String> v) { this.properties = v; return this; }
        public Builder workdir(String v) { this.workdir = v; return this; }
        public Builder pathLinks(List<String> v) { this.pathLinks = v; return this; }
        public Builder lifecycle(Lifecycle v) { this.lifecycle = v; return this; }

        public ServiceSpec build() {
            return new ServiceSpec(this);
        }
    }
}
```

`Yaml12Resolver.java`（关键：SnakeYAML 默认按 YAML 1.1 把 `no`/`on` 解析成布尔，会让 `restart: no` 变成 `false`）：

```java
package io.xac.systemd.spec;

import java.util.regex.Pattern;

import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

final class Yaml12Resolver extends Resolver {

    private static final Pattern CORE_BOOL =
            Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

    @Override
    protected void addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF");
        addImplicitResolver(Tag.INT, INT, "-+0123456789");
        addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
        addImplicitResolver(Tag.NULL, EMPTY, null);
        addImplicitResolver(Tag.MERGE, MERGE, "<");
    }
}
```

`Schema.java`：字段表 + 结构遍历。

```java
package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Schema {

    enum Kind { STRING, BOOLEAN, INTEGER, STRING_LIST, STRING_MAP, SECTION }

    static final class Node {
        final String name;
        final Kind kind;
        final boolean required;
        final Map<String, Node> children;

        private Node(String name, Kind kind, boolean required, Map<String, Node> children) {
            this.name = name;
            this.kind = kind;
            this.required = required;
            this.children = children;
        }

        static Node section(String name, boolean required, Node... children) {
            Map<String, Node> map = new LinkedHashMap<String, Node>();
            for (Node child : children) {
                map.put(child.name, child);
            }
            return new Node(name, Kind.SECTION, required, map);
        }

        static Node string(String name, boolean required) {
            return new Node(name, Kind.STRING, required, null);
        }

        static Node bool(String name) {
            return new Node(name, Kind.BOOLEAN, false, null);
        }

        static Node integer(String name) {
            return new Node(name, Kind.INTEGER, false, null);
        }

        static Node stringList(String name, boolean required) {
            return new Node(name, Kind.STRING_LIST, required, null);
        }

        static Node stringMap(String name) {
            return new Node(name, Kind.STRING_MAP, false, null);
        }
    }

    static final Node ROOT = Node.section("",
            true,
            Node.string("name", true),
            Node.string("description", false),
            Node.section("artifact", true, Node.string("jar", true)),
            Node.section("runtime", true,
                    Node.string("java", true),
                    Node.stringList("opts", true)),
            Node.stringMap("env"),
            Node.stringMap("properties"),
            Node.string("workdir", false),
            Node.stringList("path-links", false),
            Node.section("lifecycle", false,
                    Node.bool("auto-start"),
                    Node.string("restart"),
                    Node.integer("restart-sec"),
                    Node.integer("timeout-stop-sec")));

    /** 结构校验：未知字段、类型、必填。不做业务语义校验。 */
    static void check(Node node, Object value, String path, List<Violation> out) {
        if (value == null) {
            if (node.required && !node.name.isEmpty()) {
                out.add(new Violation(path, "缺失必填字段"));
            }
            return;
        }
        switch (node.kind) {
            case SECTION:
                if (!(value instanceof Map)) {
                    out.add(new Violation(path, "应为映射"));
                    return;
                }
                Map<?, ?> map = (Map<?, ?>) value;
                for (Object key : map.keySet()) {
                    if (!(key instanceof String) || !node.children.containsKey(key)) {
                        String childPath = path.isEmpty() ? String.valueOf(key) : path + "." + key;
                        out.add(new Violation(childPath, "未知字段"));
                    }
                }
                for (Node child : node.children.values()) {
                    String childPath = path.isEmpty() ? child.name : path + "." + child.name;
                    check(child, map.get(child.name), childPath, out);
                }
                return;
            case STRING:
                if (!(value instanceof String)) {
                    out.add(new Violation(path, "应为字符串"));
                }
                return;
            case BOOLEAN:
                if (!(value instanceof Boolean)) {
                    out.add(new Violation(path, "应为布尔值"));
                }
                return;
            case INTEGER:
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    out.add(new Violation(path, "应为整数"));
                }
                return;
            case STRING_LIST:
                if (!(value instanceof List)) {
                    out.add(new Violation(path, "应为字符串列表"));
                    return;
                }
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof String)) {
                        out.add(new Violation(path + "[" + i + "]", "应为字符串"));
                    }
                }
                return;
            case STRING_MAP:
                if (!(value instanceof Map)) {
                    out.add(new Violation(path, "应为映射"));
                    return;
                }
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        out.add(new Violation(path, "键应为字符串"));
                    }
                    if (!(entry.getValue() instanceof String)) {
                        out.add(new Violation(path + "." + entry.getKey(), "应为字符串"));
                    }
                }
                return;
            default:
                throw new IllegalStateException("unhandled " + node.kind);
        }
    }

    private Schema() {
    }
}
```

`SpecLoader.java`：

```java
package io.xac.systemd.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

public final class SpecLoader {

    public static final class Result {
        public final ServiceSpec spec;
        public final List<Violation> violations;

        Result(ServiceSpec spec, List<Violation> violations) {
            this.spec = spec;
            this.violations = violations;
        }
    }

    public static Result load(Path packageDir) {
        Path packageRoot = packageDir.toAbsolutePath().normalize();
        Path file = packageRoot.resolve("xac.yaml");
        List<Violation> violations = new ArrayList<Violation>();
        if (!Files.isRegularFile(file)) {
            violations.add(new Violation("xac.yaml", "包目录下没有 xac.yaml"));
            return new Result(null, violations);
        }
        Object root;
        try (InputStream in = Files.newInputStream(file);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = newYaml().load(reader);
        } catch (MarkedYAMLException e) {
            violations.add(new Violation("xac.yaml", "yaml 语法错误: " + e.getProblem()));
            return new Result(null, violations);
        } catch (IOException e) {
            violations.add(new Violation("xac.yaml", "读取失败: " + e.getMessage()));
            return new Result(null, violations);
        }

        if (!(root instanceof Map)) {
            violations.add(new Violation("", "顶层应为映射"));
            return new Result(null, violations);
        }
        Schema.check(Schema.ROOT, root, "", violations);
        if (!violations.isEmpty()) {
            return new Result(null, violations);
        }
        return new Result(build(packageRoot, (Map<?, ?>) root), violations);
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        yaml.setResolver(new Yaml12Resolver());
        return yaml;
    }

    private static ServiceSpec build(Path packageDir, Map<?, ?> root) {
        Map<?, ?> artifact = (Map<?, ?>) root.get("artifact");
        Map<?, ?> runtime = (Map<?, ?>) root.get("runtime");
        Map<?, ?> lifecycle = (Map<?, ?>) root.get("lifecycle");

        ServiceSpec.Builder builder = ServiceSpec.builder(packageDir)
                .name(str(root.get("name")))
                .description(str(root.get("description")))
                .jar(str(artifact.get("jar")))
                .java(str(runtime.get("java")))
                .opts(stringList(runtime.get("opts")))
                .env(stringMap(root.get("env")))
                .properties(stringMap(root.get("properties")))
                .workdir(str(root.get("workdir")))
                .pathLinks(stringList(root.get("path-links")));

        if (lifecycle != null) {
            builder.lifecycle(new Lifecycle(
                    bool(lifecycle.get("auto-start")),
                    str(lifecycle.get("restart")),
                    integer(lifecycle.get("restart-sec")),
                    integer(lifecycle.get("timeout-stop-sec"))));
        }
        return builder.build();
    }

    private static String str(Object value) {
        return value == null ? null : (String) value;
    }

    private static Boolean bool(Object value) {
        return value == null ? null : (Boolean) value;
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                result.add((String) item);
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                result.put((String) e.getKey(), (String) e.getValue());
            }
        }
        return result;
    }

    private SpecLoader() {
    }
}
```

注意：`xac.yaml` 里 `env` 的键顺序会被 `LinkedHashMap` 保留（SnakeYAML 用 LinkedHashMap）。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=SpecLoaderTest`
Expected: `Tests run: 7, Failures: 0`

如果 `keepsYaml12TextForRestartNo` 失败（得到 `false` 而不是 `"no"`），说明 `Yaml12Resolver` 没生效：检查 `newYaml()` 里是否调用了 `yaml.setResolver(...)`。

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: xac.yaml 模型、schema 与加载器"
```

---

### Task 3: 静态校验器

**Files:**
- Create: `src/main/java/io/xac/systemd/spec/Validator.java`
- Create: `src/main/java/io/xac/systemd/spec/ValidationRunner.java`
- Create: `src/main/java/io/xac/systemd/spec/validators/NameValidator.java`
- Create: `src/main/java/io/xac/systemd/spec/validators/EnvValidator.java`
- Create: `src/main/java/io/xac/systemd/spec/validators/PathLinksValidator.java`
- Create: `src/main/java/io/xac/systemd/spec/validators/PlaceholderValidator.java`
- Create: `src/main/java/io/xac/systemd/spec/validators/LifecycleValidator.java`
- Test: `src/test/java/io/xac/systemd/spec/validators/ValidatorsTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`、`Lifecycle`、`PathLink`、`Violation`
- Produces:
  - `interface Validator { void validate(ServiceSpec spec, List<Violation> out); }`
  - `ValidationRunner.run(ServiceSpec spec)` → `List<Violation>`（按固定顺序跑 5 个校验器）

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.spec.validators;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorsTest {

    private static final Path PKG = Paths.get("/opt/demo");

    private static ServiceSpec.Builder base() {
        return ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(asList("-Xmx512m"))
                .lifecycle(new Lifecycle(null, null, null, null));
    }

    private static List<String> asList(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }

    private static List<String> messages(List<Violation> violations) {
        List<String> result = new ArrayList<String>();
        for (Violation v : violations) {
            result.add(v.toString());
        }
        return result;
    }

    @Test
    void acceptsValidSpec() {
        assertTrue(ValidationRunner.run(base().build()).isEmpty());
    }

    @Test
    void rejectsBadName() {
        List<Violation> out = ValidationRunner.run(base().name("../etc/passwd").build());
        assertEquals(1, out.size(), messages(out).toString());
        assertEquals("name: 只能包含字母、数字和 _ . : -，不能以 . 开头，不能包含 /",
                out.get(0).toString());
    }

    @Test
    void rejectsBadEnvKeyAndNewlineValue() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("1BAD", "x");
        env.put("GOOD", "line1\nline2");
        List<Violation> out = ValidationRunner.run(base().env(env).build());
        assertEquals(2, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().startsWith("env.1BAD"));
        assertTrue(out.get(1).toString().startsWith("env.GOOD"));
    }

    @Test
    void rejectsPathLinkWithoutAbsoluteTarget() {
        List<Violation> out = ValidationRunner.run(
                base().pathLinks(asList("cfg:relative/path")).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertTrue(out.get(0).path.startsWith("path-links["), out.get(0).toString());
    }

    @Test
    void rejectsPlaceholderNotInEnv() {
        List<Violation> out = ValidationRunner.run(
                base().opts(asList("-Xmx512m", "-Dpw=${SECRET}")).build());
        assertEquals(1, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().contains("${SECRET}"), out.get(0).toString());
    }

    @Test
    void acceptsPlaceholderFromEnvAndEscapedDollar() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("SECRET", "s");
        List<Violation> out = ValidationRunner.run(base()
                .env(env)
                .opts(asList("-Dpw=${SECRET}", "-Dlit=$${NOT_A_REF}"))
                .build());
        assertTrue(out.isEmpty(), messages(out).toString());
    }

    @Test
    void rejectsBadLifecycleValues() {
        List<Violation> out = ValidationRunner.run(base()
                .lifecycle(new Lifecycle(true, "sometimes", 0, -1))
                .build());
        assertEquals(3, out.size(), messages(out).toString());
        assertTrue(out.get(0).toString().startsWith("lifecycle.restart"));
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=ValidatorsTest`
Expected: `COMPILATION ERROR`：找不到 `ValidationRunner`。

- [ ] **Step 3: 实现校验器**

`Validator.java`：

```java
package io.xac.systemd.spec;

import java.util.List;

public interface Validator {

    void validate(ServiceSpec spec, List<Violation> out);
}
```

`ValidationRunner.java`：

```java
package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.spec.validators.EnvValidator;
import io.xac.systemd.spec.validators.LifecycleValidator;
import io.xac.systemd.spec.validators.NameValidator;
import io.xac.systemd.spec.validators.PathLinksValidator;
import io.xac.systemd.spec.validators.PlaceholderValidator;

public final class ValidationRunner {

    /** 顺序固定：输出稳定，测试可断言下标。 */
    private static final List<Validator> VALIDATORS = Arrays.<Validator>asList(
            new NameValidator(),
            new EnvValidator(),
            new PathLinksValidator(),
            new PlaceholderValidator(),
            new LifecycleValidator());

    public static List<Violation> run(ServiceSpec spec) {
        List<Violation> out = new ArrayList<Violation>();
        for (Validator validator : VALIDATORS) {
            validator.validate(spec, out);
        }
        return out;
    }

    private ValidationRunner() {
    }
}
```

`validators/NameValidator.java`：

```java
package io.xac.systemd.spec.validators;

import java.util.regex.Pattern;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class NameValidator implements Validator {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.:-]+");

    @Override
    public void validate(ServiceSpec spec, java.util.List<Violation> out) {
        String name = spec.name;
        if (name == null) {
            return;
        }
        boolean ok = ALLOWED.matcher(name).matches()
                && !name.startsWith(".")
                && !name.contains("/");
        if (!ok) {
            out.add(new Violation("name",
                    "只能包含字母、数字和 _ . : -，不能以 . 开头，不能包含 /"));
        }
    }
}
```

`validators/EnvValidator.java`：

```java
package io.xac.systemd.spec.validators;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class EnvValidator implements Validator {

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        for (Map.Entry<String, String> entry : spec.env.entrySet()) {
            String path = "env." + entry.getKey();
            if (!KEY.matcher(entry.getKey()).matches()) {
                out.add(new Violation(path, "键名只能包含字母、数字和下划线，且不能以数字开头"));
            }
            String value = entry.getValue();
            if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                out.add(new Violation(path, "值不能包含换行"));
            }
        }
    }
}
```

`validators/PathLinksValidator.java`：

```java
package io.xac.systemd.spec.validators;

import java.util.List;

import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class PathLinksValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        List<String> links = spec.pathLinks;
        for (int i = 0; i < links.size(); i++) {
            String raw = links.get(i);
            if (PathLink.parse(raw, spec.packageDir) == null) {
                out.add(new Violation("path-links[" + i + "]",
                        "应为 源:目标，且目标是以 / 开头的绝对路径"));
            }
        }
    }
}
```

`validators/PlaceholderValidator.java`：

```java
package io.xac.systemd.spec.validators;

import java.util.List;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

/**
 * 找出每个 ${NAME} 并要求 NAME 在 env 中；$${ 是字面量转义，跳过。
 * 渲染时不展开，交给 systemd 在运行时展开（D13）。
 */
public final class PlaceholderValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        for (int i = 0; i < spec.opts.size(); i++) {
            scan(spec, spec.opts.get(i), "runtime.opts[" + i + "]", out);
        }
        for (java.util.Map.Entry<String, String> e : spec.properties.entrySet()) {
            scan(spec, e.getValue(), "properties." + e.getKey(), out);
        }
    }

    private static void scan(ServiceSpec spec, String text, String path, List<Violation> out) {
        if (text == null) {
            return;
        }
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$$", i)) {
                i += 2;
                continue;
            }
            if (text.startsWith("${", i)) {
                int end = text.indexOf('}', i + 2);
                if (end < 0) {
                    out.add(new Violation(path, "占位符缺少 }：" + text.substring(i)));
                    return;
                }
                String name = text.substring(i + 2, end);
                if (name.isEmpty() || !spec.env.containsKey(name)) {
                    out.add(new Violation(path, "占位符 ${" + name + "} 在 env 中不存在"));
                }
                i = end + 1;
                continue;
            }
            i++;
        }
    }
}
```

`validators/LifecycleValidator.java`：

```java
package io.xac.systemd.spec.validators;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

public final class LifecycleValidator implements Validator {

    private static final Set<String> RESTART = new HashSet<String>(Arrays.asList(
            "no", "on-success", "on-failure", "on-abnormal",
            "on-abort", "on-watchdog", "always"));

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        Lifecycle lc = spec.lifecycle;
        if (lc.restart != null && !RESTART.contains(lc.restart)) {
            out.add(new Violation("lifecycle.restart",
                    "取值应为 " + RESTART + " 之一"));
        }
        if (lc.restartSec != null && lc.restartSec <= 0) {
            out.add(new Violation("lifecycle.restart-sec", "应为正整数"));
        }
        if (lc.timeoutStopSec != null && lc.timeoutStopSec <= 0) {
            out.add(new Violation("lifecycle.timeout-stop-sec", "应为正整数"));
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=ValidatorsTest`
Expected: `Tests run: 7, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: 静态校验器"
```

---

### Task 4: 环境探针与预检

**Files:**
- Create: `src/main/java/io/xac/systemd/apply/EnvironmentProbe.java`
- Create: `src/main/java/io/xac/systemd/apply/RealEnvironmentProbe.java`
- Create: `src/main/java/io/xac/systemd/apply/Preflight.java`
- Test: `src/test/java/io/xac/systemd/apply/PreflightTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`、`Violation`、`CommandRunner`
- Produces:
  - `interface EnvironmentProbe { boolean isRoot(); boolean systemdRunning(); boolean fileReadable(Path); boolean executable(Path); }`
  - `RealEnvironmentProbe(CommandRunner)`：`isRoot()` 用 `id -u` 输出是否 `0`；`systemdRunning()` 判断 `/run/systemd/system` 是目录；`fileReadable` 用 `Files.isRegularFile`；`executable` 用 `Files.isExecutable`
  - `Preflight.check(ServiceSpec spec, EnvironmentProbe probe)` → `List<Violation>`

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.apply;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreflightTest {

    @TempDir
    Path pkg;

    private ServiceSpec spec(String jarName) {
        return ServiceSpec.builder(pkg)
                .name("demo")
                .jar(jarName)
                .java("/usr/bin/java")
                .opts(java.util.Collections.<String>emptyList())
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    private static final class FakeProbe implements EnvironmentProbe {
        boolean root = true;
        boolean systemd = true;
        boolean javaExecutable = true;

        @Override
        public boolean isRoot() { return root; }

        @Override
        public boolean systemdRunning() { return systemd; }

        @Override
        public boolean fileReadable(Path path) { return Files.isRegularFile(path); }

        @Override
        public boolean executable(Path path) { return javaExecutable; }
    }

    @Test
    void passesWhenEverythingIsFine() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        assertTrue(Preflight.check(spec("app.jar"), new FakeProbe()).isEmpty());
    }

    @Test
    void requiresJarToExist() {
        List<Violation> out = Preflight.check(spec("app.jar"), new FakeProbe());
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("artifact.jar"), out.get(0).toString());
    }

    @Test
    void requiresRootAndSystemd() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.root = false;
        probe.systemd = false;
        List<Violation> out = Preflight.check(spec("app.jar"), probe);
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).toString().contains("root"));
        assertTrue(out.get(1).toString().contains("systemd"));
    }

    @Test
    void requiresJavaToBeExecutable() throws IOException {
        Files.createFile(pkg.resolve("app.jar"));
        FakeProbe probe = new FakeProbe();
        probe.javaExecutable = false;
        List<Violation> out = Preflight.check(spec("app.jar"), probe);
        assertEquals(1, out.size());
        assertTrue(out.get(0).toString().contains("runtime.java"), out.get(0).toString());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=PreflightTest`
Expected: `COMPILATION ERROR`：找不到 `EnvironmentProbe`。

- [ ] **Step 3: 实现探针与预检**

`EnvironmentProbe.java`：

```java
package io.xac.systemd.apply;

import java.nio.file.Path;

public interface EnvironmentProbe {

    boolean isRoot();

    boolean systemdRunning();

    boolean fileReadable(Path path);

    boolean executable(Path path);
}
```

`RealEnvironmentProbe.java`：

```java
package io.xac.systemd.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;

public final class RealEnvironmentProbe implements EnvironmentProbe {

    private static final Path SYSTEMD_RUNTIME = Paths.get("/run/systemd/system");

    private final CommandRunner runner;

    public RealEnvironmentProbe(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean isRoot() {
        ExecResult result = runner.run(Arrays.asList("id", "-u"));
        return result.ok() && "0".equals(result.stdout.trim());
    }

    @Override
    public boolean systemdRunning() {
        return Files.isDirectory(SYSTEMD_RUNTIME);
    }

    @Override
    public boolean fileReadable(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public boolean executable(Path path) {
        return Files.isExecutable(path);
    }
}
```

`Preflight.java`：

```java
package io.xac.systemd.apply;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Violation;

public final class Preflight {

    /** 依赖当前机器的检查；通过后才允许改动目标机。 */
    public static List<Violation> check(ServiceSpec spec, EnvironmentProbe probe) {
        List<Violation> out = new ArrayList<Violation>();
        if (!probe.isRoot()) {
            out.add(new Violation("", "apply / destroy 需要 root 权限"));
        }
        if (!probe.systemdRunning()) {
            out.add(new Violation("", "systemd 未运行（/run/systemd/system 不存在）"));
        }
        if (!probe.fileReadable(spec.jar)) {
            out.add(new Violation("artifact.jar", "文件不存在: " + spec.jar));
        }
        if (!probe.executable(Paths.get(spec.java))) {
            out.add(new Violation("runtime.java", "不存在或不可执行: " + spec.java));
        }
        return out;
    }

    private Preflight() {
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=PreflightTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: 环境探针与预检"
```

---

### Task 5: unit 与 env 文件渲染

**Files:**
- Create: `src/main/java/io/xac/systemd/systemd/SystemdTarget.java`
- Create: `src/main/java/io/xac/systemd/systemd/UnitNaming.java`
- Create: `src/main/java/io/xac/systemd/systemd/Directive.java`
- Create: `src/main/java/io/xac/systemd/systemd/UnitRenderer.java`
- Test: `src/test/java/io/xac/systemd/systemd/UnitRendererTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`
- Produces:
  - `new SystemdTarget(Path unitDir, Path envDir)`；`SystemdTarget.defaults()`（`/etc/systemd/system`、`/etc/xac`）；字段 `unitDir`、`envDir`
  - `UnitNaming.unitName(ServiceSpec)` → `<name>.service`；`unitPath(ServiceSpec, SystemdTarget)`；`envPath(ServiceSpec, SystemdTarget)`
  - `interface Directive { String render(ServiceSpec spec); }`（返回一行；返回 null 表示不写）
  - `UnitRenderer.render(ServiceSpec, SystemdTarget)` → unit 文本；`renderEnvFile(ServiceSpec)` → env 文本

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.systemd;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitRendererTest {

    private static final Path PKG = Paths.get("/opt/demo");
    private static final SystemdTarget TARGET =
            new SystemdTarget(Paths.get("/etc/systemd/system"), Paths.get("/opt/xac"));

    private static List<String> opts(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    @Test
    void rendersFullUnit() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .description("演示服务")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m", "-XX:+UseSerialGC"))
                .properties(map("foo", "${BAR}"))
                .env(map("BAR", "hello world"))
                .workdir("run")
                .lifecycle(new Lifecycle(true, "on-failure", 5, 20))
                .build();

        String expected = String.join("\n",
                "# Managed by xac. Do not edit.",
                "[Unit]",
                "Description=演示服务",
                "",
                "[Service]",
                "Type=simple",
                "WorkingDirectory=/opt/demo/run",
                "SuccessExitStatus=143",
                "EnvironmentFile=/opt/xac/demo.env",
                "SyslogIdentifier=demo",
                "ExecStart=/usr/bin/java -Xmx512m -XX:+UseSerialGC -Dfoo=${BAR} -jar /opt/demo/app.jar",
                "Restart=on-failure",
                "RestartSec=5",
                "TimeoutStopSec=20",
                "",
                "[Install]",
                "WantedBy=multi-user.target",
                "");

        assertEquals(expected, new UnitRenderer().render(spec, TARGET));
    }

    @Test
    void omitsInstallSectionWhenAutoStartIsFalse() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m"))
                .lifecycle(new Lifecycle(false, null, null, null))
                .build();

        String expected = String.join("\n",
                "# Managed by xac. Do not edit.",
                "[Unit]",
                "",
                "[Service]",
                "Type=simple",
                "WorkingDirectory=/opt/demo",
                "SuccessExitStatus=143",
                "SyslogIdentifier=demo",
                "ExecStart=/usr/bin/java -Xmx512m -jar /opt/demo/app.jar",
                "Restart=on-failure",
                "RestartSec=5",
                "TimeoutStopSec=60",
                "");

        assertEquals(expected, new UnitRenderer().render(spec, TARGET));
    }

    @Test
    void escapesPercentAndQuotesArgumentsWithSpaces() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Dpct=100%", "-Dsp=a b", "-Dq=a\"b"))
                .lifecycle(new Lifecycle(true, "always", 1, 1))
                .build();

        String unit = new UnitRenderer().render(spec, TARGET);
        String execStart = lineOf(unit, "ExecStart=");
        assertEquals("/usr/bin/java -Dpct=100%% \"-Dsp=a b\" \"-Dq=a\\\"b\" "
                + "-jar /opt/demo/app.jar", execStart);
        assertEquals("always", lineOf(unit, "Restart=").substring("Restart=".length()));
    }

    @Test
    void rendersEnvFileWithQuotedValues() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts())
                .env(map("BAR", "hello world", "EMPTY", "", "QUOTE", "a\"b"))
                .build();

        assertEquals(String.join("\n",
                "BAR=\"hello world\"",
                "EMPTY=",
                "QUOTE=\"a\\\"b\"",
                ""), new UnitRenderer().renderEnvFile(spec));
    }

    @Test
    void renderingIsDeterministic() {
        ServiceSpec spec = ServiceSpec.builder(PKG)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(opts("-Xmx512m"))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        UnitRenderer renderer = new UnitRenderer();
        assertEquals(renderer.render(spec, TARGET), renderer.render(spec, TARGET));
        assertEquals(UnitNaming.unitName(spec), "demo.service");
        assertEquals(Paths.get("/opt/xac/demo.env"), UnitNaming.envPath(spec, TARGET));
    }

    private static String lineOf(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("没有以 " + prefix + " 开头的行");
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=UnitRendererTest`
Expected: `COMPILATION ERROR`：找不到 `UnitRenderer`。

- [ ] **Step 3: 实现渲染器**

`SystemdTarget.java`：

```java
package io.xac.systemd.systemd;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class SystemdTarget {

    public final Path unitDir;
    public final Path envDir;

    public SystemdTarget(Path unitDir, Path envDir) {
        this.unitDir = unitDir;
        this.envDir = envDir;
    }

    public static SystemdTarget defaults() {
        return new SystemdTarget(Paths.get("/etc/systemd/system"), Paths.get("/etc/xac"));
    }
}
```

`UnitNaming.java`：

```java
package io.xac.systemd.systemd;

import java.nio.file.Path;

import io.xac.systemd.spec.ServiceSpec;

public final class UnitNaming {

    public static String unitName(ServiceSpec spec) {
        return spec.name + ".service";
    }

    public static String envFileName(ServiceSpec spec) {
        return spec.name + ".env";
    }

    public static Path unitPath(ServiceSpec spec, SystemdTarget target) {
        return target.unitDir.resolve(unitName(spec));
    }

    public static Path envPath(ServiceSpec spec, SystemdTarget target) {
        return target.envDir.resolve(envFileName(spec));
    }

    private UnitNaming() {
    }
}
```

`Directive.java`：

```java
package io.xac.systemd.systemd;

import io.xac.systemd.spec.ServiceSpec;

public interface Directive {

    /** 返回一行 unit 指令；null 表示这条指令不出现。 */
    String render(ServiceSpec spec);
}
```

`UnitRenderer.java`：

```java
package io.xac.systemd.systemd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.xac.systemd.spec.ServiceSpec;

public final class UnitRenderer {

    private static final String HEADER = "# Managed by xac. Do not edit.";

    public String render(ServiceSpec spec, SystemdTarget target) {
        List<List<String>> sections = new ArrayList<List<String>>();

        List<String> unit = new ArrayList<String>();
        unit.add("[Unit]");
        addIfPresent(unit, "Description=" + nullToEmpty(spec.description));
        sections.add(unit);

        // 顺序固定；新增指令追加在这里，不要重排既有行（设计文档 §5.2）。
        List<Directive> directives = new ArrayList<Directive>();
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "Type=simple";
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "WorkingDirectory=" + workingDirectory(s);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "SuccessExitStatus=143";
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return s.env.isEmpty()
                        ? null
                        : "EnvironmentFile=" + UnitNaming.envPath(s, target);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "SyslogIdentifier=" + s.name;
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "ExecStart=" + execStart(s);
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "Restart=" + s.lifecycle.restartOrDefault();
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "RestartSec=" + s.lifecycle.restartSecOrDefault();
            }
        });
        directives.add(new Directive() {
            @Override
            public String render(ServiceSpec s) {
                return "TimeoutStopSec=" + s.lifecycle.timeoutStopSecOrDefault();
            }
        });

        List<String> service = new ArrayList<String>();
        service.add("[Service]");
        for (Directive directive : directives) {
            addIfPresent(service, directive.render(spec));
        }
        sections.add(service);

        if (spec.lifecycle.autoStartOrDefault()) {
            List<String> install = new ArrayList<String>();
            install.add("[Install]");
            install.add("WantedBy=multi-user.target");
            sections.add(install);
        }

        StringBuilder out = new StringBuilder();
        out.append(HEADER).append('\n');
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            for (String line : sections.get(i)) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    public String execStart(ServiceSpec spec) {
        List<String> argv = new ArrayList<String>();
        argv.add(spec.java);
        for (String opt : spec.opts) {
            argv.add(opt);
        }
        for (Map.Entry<String, String> e : spec.properties.entrySet()) {
            argv.add("-D" + e.getKey() + "=" + e.getValue());
        }
        argv.add("-jar");
        argv.add(spec.jar.toString());

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(escape(argv.get(i)));
        }
        return out.toString();
    }

    public String renderEnvFile(ServiceSpec spec) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : spec.env.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out.append(e.getKey()).append('=');
            if (!value.isEmpty()) {
                out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * 只做两件事：% → %%，以及需要引号时加双引号（引号内转义 \ 与 "）。
     * 需要引号的字符：空格、制表符、双引号、反斜杠。
     * $ 不转义：${VAR} 由 systemd 在运行时展开，$${ 是用户写的字面量转义，两者语义正好一致（D13）。
     */
    static String escape(String arg) {
        String escaped = arg.replace("%", "%%");
        boolean quote = escaped.indexOf(' ') >= 0
                || escaped.indexOf('\t') >= 0
                || escaped.indexOf('"') >= 0
                || escaped.indexOf('\\') >= 0;
        if (!quote) {
            return escaped;
        }
        return "\"" + escaped.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String workingDirectory(ServiceSpec spec) {
        return spec.workdir == null ? spec.packageDir.toString() : spec.workdir.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line == null || line.isEmpty() || line.endsWith("=")) {
            return;
        }
        lines.add(line);
    }
}
```

注意 `addIfPresent` 的 `line.endsWith("=")` 用于跳过空 `Description=`：`"Description=" + ""` 正好以 `=` 结尾。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=UnitRendererTest`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: unit 与 env 文件渲染"
```

---

### Task 6: apply 骨架、path-links 与 workdir

**Files:**
- Create: `src/main/java/io/xac/systemd/apply/Action.java`
- Create: `src/main/java/io/xac/systemd/apply/ActionFailure.java`
- Create: `src/main/java/io/xac/systemd/apply/ApplyContext.java`
- Create: `src/main/java/io/xac/systemd/apply/FileWriter.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/PathLinksAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/WorkdirAction.java`
- Test: `src/test/java/io/xac/systemd/apply/actions/PathLinksActionTest.java`
- Test: `src/test/java/io/xac/systemd/apply/actions/WorkdirActionTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`、`SystemdTarget`、`CommandRunner`、`UnitRenderer`、`PathLink`
- Produces:
  - `interface Action { String describe(ApplyContext ctx); void run(ApplyContext ctx); }`（describe 用于 `--dry-run` 与正常输出，必须包含具体路径）
  - `class ActionFailure extends RuntimeException`（`new ActionFailure(String message)`）
  - `new ApplyContext(ServiceSpec spec, SystemdTarget target, CommandRunner runner, boolean dryRun)`；字段 `spec`、`target`、`runner`、`dryRun`、`unitContent`（构造时渲染一次）、`envContent`
  - `FileWriter.write(Path path, String content, String posixPermissions)` → 同目录临时文件 + 原子替换；不支持 posix 的文件系统跳过权限设置
  - `new PathLinksAction()`、`new WorkdirAction()`（均实现 `Action`）

- [ ] **Step 1: 写失败测试**

`PathLinksActionTest`（Windows 上无权限建软链接时用 assumption 跳过）：

```java
package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathLinksActionTest {

    @TempDir
    Path root;

    private ApplyContext context(String linkSpec) {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .pathLinks(new ArrayList<String>(Arrays.asList(linkSpec)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        SystemdTarget target = new SystemdTarget(root.resolve("units"), root.resolve("env"));
        return new ApplyContext(spec, target, new RecordingRunner(), false);
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("probe-link");
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void createsLinkWhenTargetMissing() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");

        new PathLinksAction().run(context(source + ":" + target));

        assertTrue(Files.isSymbolicLink(target));
        assertEquals(source, Files.readSymbolicLink(target));
    }

    @Test
    void isIdempotentWhenLinkAlreadyPointsToSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("link.txt");
        Files.createSymbolicLink(target, source);

        new PathLinksAction().run(context(source + ":" + target));

        assertEquals(source, Files.readSymbolicLink(target));
        assertTrue(Files.notExists(root.resolve("link.txt.bak")));
    }

    @Test
    void backsUpRealDirectoryBeforeLinking() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createDirectories(root.resolve("payload"));
        Path target = Files.createDirectories(root.resolve("link"));
        Files.write(target.resolve("old.txt"), "old".getBytes(StandardCharsets.UTF_8));

        new PathLinksAction().run(context(source + ":" + target));

        assertTrue(Files.isSymbolicLink(target));
        assertEquals(source, Files.readSymbolicLink(target));
        assertTrue(Files.isRegularFile(root.resolve("link.bak").resolve("old.txt")));
    }

    @Test
    void failsWhenTargetParentDoesNotExist() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Path source = Files.createFile(root.resolve("payload.txt"));
        Path target = root.resolve("missing-dir").resolve("link.txt");

        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new PathLinksAction().run(context(source + ":" + target)));
        assertTrue(failure.getMessage().contains("父目录"), failure.getMessage());
    }
}
```

`WorkdirActionTest`：

```java
package io.xac.systemd.apply.actions;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkdirActionTest {

    @TempDir
    Path root;

    private ApplyContext context(String workdir) {
        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(Collections.<String>emptyList())
                .workdir(workdir)
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
        return new ApplyContext(spec, new SystemdTarget(root.resolve("units"), root.resolve("env")),
                new RecordingRunner(), false);
    }

    @Test
    void createsLastSegmentOnly() throws IOException {
        Files.createDirectories(root.resolve("run"));
        new WorkdirAction().run(context("run/work"));
        assertTrue(Files.isDirectory(root.resolve("run/work")));
    }

    @Test
    void requiresParentToExist() {
        ActionFailure failure = assertThrows(ActionFailure.class,
                () -> new WorkdirAction().run(context("missing/work")));
        assertTrue(failure.getMessage().contains("父目录"), failure.getMessage());
    }

    @Test
    void skipsWhenWorkdirIsNotConfigured() {
        new WorkdirAction().run(context(null));
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=PathLinksActionTest+WorkdirActionTest`
Expected: `COMPILATION ERROR`：找不到 `ApplyContext`。

- [ ] **Step 3: 实现骨架与两个动作**

`Action.java`：

```java
package io.xac.systemd.apply;

public interface Action {

    String describe(ApplyContext ctx);

    void run(ApplyContext ctx);
}
```

`ActionFailure.java`：

```java
package io.xac.systemd.apply;

public class ActionFailure extends RuntimeException {

    public ActionFailure(String message) {
        super(message);
    }

    public ActionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ApplyContext.java`：

```java
package io.xac.systemd.apply;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitRenderer;

public final class ApplyContext {

    public final ServiceSpec spec;
    public final SystemdTarget target;
    public final CommandRunner runner;
    public final boolean dryRun;
    public final String unitContent;
    public final String envContent;

    public ApplyContext(ServiceSpec spec, SystemdTarget target, CommandRunner runner, boolean dryRun) {
        this.spec = spec;
        this.target = target;
        this.runner = runner;
        this.dryRun = dryRun;
        UnitRenderer renderer = new UnitRenderer();
        this.unitContent = renderer.render(spec, target);
        this.envContent = renderer.renderEnvFile(spec);
    }
}
```

`FileWriter.java`：

```java
package io.xac.systemd.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class FileWriter {

    public static void write(Path path, String content, String posixPermissions) throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("目录不存在: " + parent);
        }
        Path tmp = Files.createTempFile(parent, ".xac-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString(posixPermissions);
                Files.setPosixFilePermissions(tmp, perms);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private FileWriter() {
    }
}
```

`actions/PathLinksAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.spec.PathLink;

public final class PathLinksAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "建立 path-links: " + ctx.spec.pathLinks;
    }

    @Override
    public void run(ApplyContext ctx) {
        for (String raw : ctx.spec.pathLinks) {
            PathLink link = PathLink.parse(raw, ctx.spec.packageDir);
            if (link == null) {
                throw new ActionFailure("非法的 path-links 项: " + raw);
            }
            try {
                ensureLink(link);
            } catch (IOException e) {
                throw new ActionFailure("建立软链接失败 " + link.target + ": " + e.getMessage(), e);
            }
        }
    }

    private static void ensureLink(PathLink link) throws IOException {
        Path parent = link.target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("目标的父目录不存在: " + parent);
        }
        if (Files.isSymbolicLink(link.target)) {
            if (Files.readSymbolicLink(link.target).equals(link.source)) {
                return;
            }
            backup(link.target);
        } else if (Files.exists(link.target)) {
            backup(link.target);
        }
        Files.createSymbolicLink(link.target, link.source);
    }

    private static void backup(Path target) throws IOException {
        Path backup = Paths.get(target.toString() + ".bak");
        if (Files.exists(backup)) {
            String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            backup = Paths.get(target.toString() + ".bak." + stamp);
        }
        Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
    }
}
```

`actions/WorkdirAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;

public final class WorkdirAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return ctx.spec.workdir == null
                ? "跳过工作目录（未配置 workdir）"
                : "创建工作目录 " + ctx.spec.workdir;
    }

    @Override
    public void run(ApplyContext ctx) {
        Path workdir = ctx.spec.workdir;
        if (workdir == null || Files.isDirectory(workdir)) {
            return;
        }
        Path parent = workdir.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ActionFailure("工作目录的父目录不存在: " + parent);
        }
        try {
            Files.createDirectory(workdir);
        } catch (IOException e) {
            throw new ActionFailure("创建工作目录失败 " + workdir + ": " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=PathLinksActionTest+WorkdirActionTest`
Expected: `Tests run: 7, Failures: 0`（Windows 上无软链接权限时 `PathLinksActionTest` 会 skipped 3–4 个）

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: apply 骨架、path-links 与 workdir 动作"
```

---

### Task 7: 其余动作与 ApplyRunner

**Files:**
- Create: `src/main/java/io/xac/systemd/apply/actions/EnvFileAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/UnitFileAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/DaemonReloadAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/AutoStartAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/RestartAction.java`
- Create: `src/main/java/io/xac/systemd/apply/ApplyRunner.java`
- Test: `src/test/java/io/xac/systemd/apply/ApplyRunnerTest.java`

**Interfaces:**
- Consumes: `ApplyContext`、`Action`、`ActionFailure`、`FileWriter`、`UnitNaming`、`CommandRunner`
- Produces:
  - 五个动作类，均实现 `Action`
  - `ApplyRunner.actions()` → `List<Action>`（固定 7 个、固定顺序）
  - `ApplyRunner.run(ApplyContext ctx, PrintStream out)` → `int`（0 成功 / 1 某个动作失败并中止）

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.apply;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyRunnerTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(Boolean autoStart, Map<String, String> env) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(autoStart, null, null, null))
                .build();
    }

    private static Map<String, String> env(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String run(ApplyContext ctx) throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));
        assertEquals(0, code, buffer.toString("UTF-8"));
        return buffer.toString("UTF-8");
    }

    @Test
    void writesFilesThenReloadsEnablesAndRestarts() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        run(ctx);

        assertEquals(ctx.unitContent, read(UnitNaming.unitPath(ctx.spec, ctx.target)));
        assertEquals(ctx.envContent, read(UnitNaming.envPath(ctx.spec, ctx.target)));
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "daemon-reload"),
                Arrays.asList("systemctl", "enable", "demo.service"),
                Arrays.asList("systemctl", "restart", "demo.service")), runner.calls());
    }

    @Test
    void disablesWhenAutoStartIsFalse() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(false, env("BAR", "b")), target(), runner, false);

        run(ctx);

        assertEquals(Arrays.asList("systemctl", "disable", "demo.service"), runner.calls().get(1));
        assertFalse(ctx.unitContent.contains("[Install]"));
    }

    @Test
    void deletesEnvFileWhenEnvIsEmpty() throws IOException {
        Files.createDirectories(target().envDir);
        Path envFile = target().envDir.resolve("demo.env");
        Files.write(envFile, "OLD=1\n".getBytes(StandardCharsets.UTF_8));

        ApplyContext ctx = new ApplyContext(spec(true, Collections.<String, String>emptyMap()),
                target(), new RecordingRunner(), false);
        run(ctx);

        assertFalse(Files.exists(envFile));
        assertFalse(ctx.unitContent.contains("EnvironmentFile="));
    }

    @Test
    void stopsAtFirstFailure() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(1, "", "boom"));
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, false);

        Files.createDirectories(target().unitDir);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(1, code);
        assertEquals(1, runner.calls().size());
        assertTrue(buffer.toString("UTF-8").contains("boom"), buffer.toString("UTF-8"));
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        RecordingRunner runner = new RecordingRunner();
        ApplyContext ctx = new ApplyContext(spec(true, env("BAR", "b")), target(), runner, true);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new ApplyRunner().run(ctx, new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(runner.calls().isEmpty());
        assertFalse(Files.exists(target().unitDir.resolve("demo.service")));
        List<String> lines = Arrays.asList(buffer.toString("UTF-8").split("\n"));
        assertEquals(7, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("would "), lines.toString());
        assertTrue(lines.get(0).contains("path-links"), lines.toString());
        assertTrue(lines.get(6).contains("restart"), lines.toString());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=ApplyRunnerTest`
Expected: `COMPILATION ERROR`：找不到 `ApplyRunner`。

- [ ] **Step 3: 实现五个动作与运行器**

`actions/EnvFileAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.FileWriter;
import io.xac.systemd.systemd.UnitNaming;

public final class EnvFileAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        return ctx.spec.env.isEmpty()
                ? "删除 " + envPath + "（env 为空）"
                : "写 " + envPath + " (0600)";
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        try {
            if (ctx.spec.env.isEmpty()) {
                Files.deleteIfExists(envPath);
                return;
            }
            Path dir = envPath.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            FileWriter.write(envPath, ctx.envContent, "rw-------");
        } catch (IOException e) {
            throw new ActionFailure("写 env 文件失败 " + envPath + ": " + e.getMessage(), e);
        }
    }
}
```

`actions/UnitFileAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.FileWriter;
import io.xac.systemd.systemd.UnitNaming;

public final class UnitFileAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "写 " + UnitNaming.unitPath(ctx.spec, ctx.target) + " (0644)";
    }

    @Override
    public void run(ApplyContext ctx) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        try {
            FileWriter.write(unitPath, ctx.unitContent, "rw-r--r--");
        } catch (IOException e) {
            throw new ActionFailure("写 unit 文件失败 " + unitPath + ": " + e.getMessage(), e);
        }
    }
}
```

`actions/DaemonReloadAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;

public final class DaemonReloadAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl daemon-reload";
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(Arrays.asList("systemctl", "daemon-reload"));
        if (!result.ok()) {
            throw new ActionFailure("systemctl daemon-reload 失败（退出码 " + result.exitCode
                    + "）: " + result.stderr.trim());
        }
    }
}
```

`actions/AutoStartAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class AutoStartAction implements Action {

    private static String verb(ApplyContext ctx) {
        return ctx.spec.lifecycle.autoStartOrDefault() ? "enable" : "disable";
    }

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl " + verb(ctx) + " " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        String verb = verb(ctx);
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", verb, UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            throw new ActionFailure("systemctl " + verb + " 失败（退出码 " + result.exitCode
                    + "）: " + result.stderr.trim());
        }
    }
}
```

`actions/RestartAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class RestartAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl restart " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", "restart", UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            throw new ActionFailure("systemctl restart 失败（退出码 " + result.exitCode
                    + "）: " + result.stderr.trim());
        }
    }
}
```

`ApplyRunner.java`：

```java
package io.xac.systemd.apply;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.apply.actions.AutoStartAction;
import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.EnvFileAction;
import io.xac.systemd.apply.actions.PathLinksAction;
import io.xac.systemd.apply.actions.RestartAction;
import io.xac.systemd.apply.actions.UnitFileAction;
import io.xac.systemd.apply.actions.WorkdirAction;

public final class ApplyRunner {

    /** 顺序是契约的一部分（设计文档 §6.2）：先建链接，再建目录，再写文件，最后动服务。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new PathLinksAction(),
                new WorkdirAction(),
                new EnvFileAction(),
                new UnitFileAction(),
                new DaemonReloadAction(),
                new AutoStartAction(),
                new RestartAction());
    }

    public int run(ApplyContext ctx, PrintStream out) {
        for (Action action : actions()) {
            String description = action.describe(ctx);
            if (ctx.dryRun) {
                out.println("would " + description);
                continue;
            }
            out.println(description);
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                out.println("失败: " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=ApplyRunnerTest`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: apply 七动作与 ApplyRunner"
```

---

### Task 8: CLI 骨架与 apply 命令

**Files:**
- Create: `src/main/java/io/xac/systemd/cli/Main.java`
- Create: `src/main/java/io/xac/systemd/cli/RootCommand.java`
- Create: `src/main/java/io/xac/systemd/cli/ApplyCommand.java`
- Create: `src/main/java/io/xac/systemd/cli/SpecCommands.java`
- Test: `src/test/java/io/xac/systemd/cli/CliTest.java`

**Interfaces:**
- Consumes: `SpecLoader`、`ValidationRunner`、`Preflight`、`RealEnvironmentProbe`、`ApplyContext`、`ApplyRunner`、`SystemdTarget`、`ProcessRunner`
- Produces:
  - `Main.main(String[])`：`System.exit(new CommandLine(new RootCommand()).execute(args))`
  - `RootCommand`：`xac` 根命令，无子命令时打印 usage 并返回 2
  - `ApplyCommand`：`xac apply [<package-dir>] [--dry-run]`，返回 0/1/2
  - `SpecCommands.load(Path pkg)` → `Loaded`：字段 `ServiceSpec spec`（校验失败为 null）、`List<Violation> violations`（静态 + 环境检查合并结果）

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliTest {

    @TempDir
    Path root;

    private static int execute(String... args) {
        return new CommandLine(new RootCommand()).execute(args);
    }

    @Test
    void printsHelp() {
        assertEquals(0, execute("--help"));
    }

    @Test
    void noSubcommandIsUsageError() {
        assertEquals(2, execute());
    }

    @Test
    void missingXacYamlIsInputError() {
        assertEquals(2, execute("apply", root.toString()));
    }

    @Test
    void unknownSubcommandIsUsageError() {
        assertEquals(2, execute("frobnicate"));
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=CliTest`
Expected: `COMPILATION ERROR`：找不到 `RootCommand`。

- [ ] **Step 3: 实现 CLI**

`Main.java`：

```java
package io.xac.systemd.cli;

import picocli.CommandLine;

public final class Main {

    public static void main(String[] args) {
        System.exit(new CommandLine(new RootCommand()).execute(args));
    }

    private Main() {
    }
}
```

`RootCommand.java`（子命令列表随 Task 9–11 追加）：

```java
package io.xac.systemd.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "xac",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "把 Java 服务安装成 systemd unit",
        subcommands = {ApplyCommand.class})
public final class RootCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 2;
    }

    public static CommandLine newCommandLine() {
        return new CommandLine(new RootCommand());
    }
}
```

`SpecCommands.java`：

```java
package io.xac.systemd.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.apply.Preflight;
import io.xac.systemd.apply.RealEnvironmentProbe;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.SpecLoader;
import io.xac.systemd.spec.ValidationRunner;
import io.xac.systemd.spec.Violation;

final class SpecCommands {

    static final class Loaded {
        final ServiceSpec spec;
        final List<Violation> violations;

        Loaded(ServiceSpec spec, List<Violation> violations) {
            this.spec = spec;
            this.violations = violations;
        }
    }

    /** 加载 + 静态校验 + 环境预检；任何一步失败都返回 spec == null。 */
    static Loaded load(Path packageDir) {
        SpecLoader.Result parsed = SpecLoader.load(packageDir);
        List<Violation> violations = new ArrayList<Violation>(parsed.violations);
        if (parsed.spec == null) {
            return new Loaded(null, violations);
        }
        violations.addAll(ValidationRunner.run(parsed.spec));
        if (violations.isEmpty()) {
            violations.addAll(Preflight.check(parsed.spec, new RealEnvironmentProbe(new ProcessRunner())));
        }
        return new Loaded(violations.isEmpty() ? parsed.spec : null, violations);
    }

    static void report(List<Violation> violations) {
        for (Violation violation : violations) {
            System.err.println(violation);
        }
    }

    private SpecCommands() {
    }
}
```

`ApplyCommand.java`：

```java
package io.xac.systemd.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.ApplyRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "apply", description = "安装或更新服务：写文件、reload、enable、restart")
public final class ApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Option(names = "--dry-run", description = "只打印将执行的动作，不做任何改动")
    boolean dryRun;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.load(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        ApplyContext ctx = new ApplyContext(loaded.spec, SystemdTarget.defaults(),
                new ProcessRunner(), dryRun);
        return new ApplyRunner().run(ctx, System.out);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=CliTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: CLI 骨架与 apply 命令"
```

---

### Task 9: status 命令

**Files:**
- Create: `src/main/java/io/xac/systemd/systemd/SystemctlShow.java`
- Create: `src/main/java/io/xac/systemd/status/StatusResult.java`
- Create: `src/main/java/io/xac/systemd/status/StatusRunner.java`
- Create: `src/main/java/io/xac/systemd/cli/StatusCommand.java`
- Modify: `src/main/java/io/xac/systemd/cli/RootCommand.java`（subcommands 加 `StatusCommand.class`）
- Modify: `src/main/java/io/xac/systemd/cli/SpecCommands.java`（新增 `parse(Path)`：只做加载 + 静态校验，不要求 root）
- Test: `src/test/java/io/xac/systemd/status/StatusRunnerTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`、`SystemdTarget`、`CommandRunner`、`UnitRenderer`、`UnitNaming`、`PathLink`
- Produces:
  - `SystemctlShow.show(CommandRunner, String unitName)` → `Map<String,String>`（`Key=Value` 行；命令失败返回空 map）
  - `StatusRunner.run(ServiceSpec, SystemdTarget, CommandRunner)` → `StatusResult`
  - `StatusResult`：字段 `service`、`packageDir`、`unitPath`、`unitInSync`、`envPath`、`envInSync`、`show`、`enabled`、`wantEnabled`、`linkProblems`；方法 `boolean healthy()`、`String format()`

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.status;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusRunnerTest {

    @TempDir
    Path root;

    private static final String SHOW = String.join("\n",
            "LoadState=loaded",
            "ActiveState=active",
            "SubState=running",
            "UnitFileState=enabled",
            "MainPID=1234",
            "ActiveEnterTimestamp=Mon 2026-11-10 19:00:00 CST",
            "FragmentPath=/etc/systemd/system/demo.service",
            "");

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec(Map<String, String> env) {
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private static Map<String, String> env() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("BAR", "b");
        return map;
    }

    private RecordingRunner runnerWithShow() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(0, SHOW, ""));
        return runner;
    }

    @Test
    void reportsHealthyAfterApplyState() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        assertTrue(result.healthy(), result.format());
        assertEquals(0, result.drift().size(), result.format());
    }

    @Test
    void detectsOutOfSyncUnitAndEnv() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        Files.write(UnitNaming.unitPath(spec, target),
                "old".getBytes(StandardCharsets.UTF_8));

        StatusResult result = new StatusRunner().run(spec, target, runnerWithShow());

        assertFalse(result.healthy());
        assertTrue(result.drift().get(0).contains("unit"), result.drift().toString());
        assertTrue(result.drift().contains("env 文件不存在: " + UnitNaming.envPath(spec, target)),
                result.drift().toString());
    }

    @Test
    void formatsReport() throws IOException {
        ServiceSpec spec = spec(env());
        SystemdTarget target = target();
        Files.createDirectories(target.unitDir);
        Files.createDirectories(target.envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target),
                renderer.render(spec, target).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));

        String expected = String.join("\n",
                "service: demo",
                "package: " + root.toAbsolutePath().normalize(),
                "unit: " + UnitNaming.unitPath(spec, target) + " (in sync)",
                "env: " + UnitNaming.envPath(spec, target) + " (in sync)",
                "active: active (running) since Mon 2026-11-10 19:00:00 CST",
                "pid: 1234",
                "auto-start: enabled",
                "");

        assertEquals(expected,
                new StatusRunner().run(spec, target, runnerWithShow()).format());
    }

    @Test
    void treatsMissingUnitAsDrift() {
        ServiceSpec spec = spec(Collections.<String, String>emptyMap());
        StatusResult result = new StatusRunner().run(spec, target(), runnerWithShow());
        assertFalse(result.healthy());
        assertTrue(result.format().contains("(missing)"), result.format());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=StatusRunnerTest`
Expected: `COMPILATION ERROR`：找不到 `StatusResult`。

- [ ] **Step 3: 实现查询与报告**

`systemd/SystemctlShow.java`：

```java
package io.xac.systemd.systemd;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;

public final class SystemctlShow {

    private static final List<String> PROPERTIES = Arrays.asList(
            "LoadState", "ActiveState", "SubState", "UnitFileState",
            "MainPID", "ActiveEnterTimestamp", "FragmentPath");

    public static Map<String, String> show(CommandRunner runner, String unitName) {
        StringBuilder property = new StringBuilder("--property=");
        for (int i = 0; i < PROPERTIES.size(); i++) {
            if (i > 0) {
                property.append(',');
            }
            property.append(PROPERTIES.get(i));
        }
        ExecResult result = runner.run(
                Arrays.asList("systemctl", "show", unitName, property.toString()));
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (!result.ok()) {
            return values;
        }
        for (String line : result.stdout.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    private SystemctlShow() {
    }
}
```

`status/StatusResult.java`：

```java
package io.xac.systemd.status;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StatusResult {

    public final String service;
    public final Path packageDir;
    public final Path unitPath;
    public final boolean unitInstalled;
    public final boolean unitInSync;
    public final Path envPath;
    public final boolean envConfigured;
    public final boolean envInSync;
    public final Map<String, String> show;
    public final boolean enabled;
    public final boolean wantEnabled;
    public final List<String> linkProblems;

    public StatusResult(String service, Path packageDir, Path unitPath, boolean unitInstalled,
                        boolean unitInSync, Path envPath, boolean envConfigured, boolean envInSync,
                        Map<String, String> show, boolean enabled, boolean wantEnabled,
                        List<String> linkProblems) {
        this.service = service;
        this.packageDir = packageDir;
        this.unitPath = unitPath;
        this.unitInstalled = unitInstalled;
        this.unitInSync = unitInSync;
        this.envPath = envPath;
        this.envConfigured = envConfigured;
        this.envInSync = envInSync;
        this.show = show;
        this.enabled = enabled;
        this.wantEnabled = wantEnabled;
        this.linkProblems = linkProblems;
    }

    public List<String> drift() {
        List<String> drift = new ArrayList<String>();
        if (!unitInSync) {
            drift.add(unitInstalled
                    ? "unit 文件与配置不一致: " + unitPath
                    : "unit 未安装: " + unitPath);
        }
        if (!envInSync) {
            drift.add("env 文件不存在或与配置不一致: " + envPath);
        }
        if (enabled != wantEnabled) {
            drift.add(wantEnabled ? "未设置开机自启" : "仍处于开机自启状态");
        }
        drift.addAll(linkProblems);
        return drift;
    }

    public boolean healthy() {
        return drift().isEmpty();
    }

    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("service: ").append(service).append('\n');
        out.append("package: ").append(packageDir).append('\n');
        out.append("unit: ").append(unitPath).append(unitState()).append('\n');
        if (envConfigured) {
            out.append("env: ").append(envPath)
                    .append(envInSync ? " (in sync)" : " (out of sync)").append('\n');
        } else {
            out.append("env: (未配置)\n");
        }
        out.append("active: ").append(value("ActiveState", "unknown"));
        String sub = show.get("SubState");
        if (sub != null && !sub.isEmpty()) {
            out.append(" (").append(sub).append(')');
        }
        String since = show.get("ActiveEnterTimestamp");
        if (since != null && !since.isEmpty()) {
            out.append(" since ").append(since);
        }
        out.append('\n');
        out.append("pid: ").append(value("MainPID", "unknown")).append('\n');
        out.append("auto-start: ").append(enabled ? "enabled" : "disabled").append('\n');
        for (String line : drift()) {
            out.append("drift: ").append(line).append('\n');
        }
        return out.toString();
    }

    private String unitState() {
        if (unitInSync) {
            return " (in sync)";
        }
        return unitInstalled ? " (out of sync)" : " (missing)";
    }

    private String value(String key, String fallback) {
        String value = show.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
```

`status/StatusRunner.java`：

```java
package io.xac.systemd.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.spec.PathLink;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemctlShow;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;

public final class StatusRunner {

    public StatusResult run(ServiceSpec spec, SystemdTarget target, CommandRunner runner) {
        UnitRenderer renderer = new UnitRenderer();
        Path unitPath = UnitNaming.unitPath(spec, target);
        Path envPath = UnitNaming.envPath(spec, target);

        boolean unitInSync = contentEquals(unitPath, renderer.render(spec, target));
        boolean envInSync = spec.env.isEmpty()
                ? !Files.exists(envPath)
                : contentEquals(envPath, renderer.renderEnvFile(spec));

        List<String> linkProblems = checkLinks(spec);
        java.util.Map<String, String> show = SystemctlShow.show(runner, UnitNaming.unitName(spec));

        boolean wantEnabled = spec.lifecycle.autoStartOrDefault();
        boolean enabled = "enabled".equals(show.get("UnitFileState"));
        return new StatusResult(spec.name, spec.packageDir, unitPath, Files.isRegularFile(unitPath),
                unitInSync, envPath, !spec.env.isEmpty(), envInSync, show, enabled, wantEnabled,
                linkProblems);
    }

    private static boolean contentEquals(Path path, String expected) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            return expected.equals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> checkLinks(ServiceSpec spec) {
        List<String> problems = new ArrayList<String>();
        for (int i = 0; i < spec.pathLinks.size(); i++) {
            PathLink link = PathLink.parse(spec.pathLinks.get(i), spec.packageDir);
            if (link == null) {
                continue;
            }
            try {
                boolean ok = Files.isSymbolicLink(link.target)
                        && Files.readSymbolicLink(link.target).equals(link.source);
                if (!ok) {
                    problems.add("path-links[" + i + "] 未生效: " + link.target);
                }
            } catch (IOException e) {
                problems.add("path-links[" + i + "] 无法检查: " + link.target + " (" + e.getMessage() + ")");
            }
        }
        return problems;
    }
}
```

`cli/StatusCommand.java`：

```java
package io.xac.systemd.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.status.StatusResult;
import io.xac.systemd.status.StatusRunner;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "status", description = "报告安装与运行状态；退出码 0 一致，1 有漂移")
public final class StatusCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.parse(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        StatusResult result = new StatusRunner().run(loaded.spec, SystemdTarget.defaults(),
                new ProcessRunner());
        System.out.print(result.format());
        return result.healthy() ? 0 : 1;
    }
}
```

同时给 `SpecCommands` 增加（`load` 复用 `parse`）：

```java
    static Loaded parse(Path packageDir) {
        SpecLoader.Result parsed = SpecLoader.load(packageDir);
        List<Violation> violations = new ArrayList<Violation>(parsed.violations);
        if (parsed.spec == null) {
            return new Loaded(null, violations);
        }
        violations.addAll(ValidationRunner.run(parsed.spec));
        return new Loaded(violations.isEmpty() ? parsed.spec : null, violations);
    }

    static Loaded load(Path packageDir) {
        Loaded parsed = parse(packageDir);
        if (parsed.spec == null) {
            return parsed;
        }
        List<Violation> violations = new ArrayList<Violation>(parsed.violations);
        violations.addAll(Preflight.check(parsed.spec, new RealEnvironmentProbe(new ProcessRunner())));
        return new Loaded(violations.isEmpty() ? parsed.spec : null, violations);
    }
```

`RootCommand` 的 `subcommands` 改为 `{ApplyCommand.class, StatusCommand.class}`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=StatusRunnerTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: status 命令"
```

---

### Task 10: logs 命令

**Files:**
- Create: `src/main/java/io/xac/systemd/logs/LogsRunner.java`
- Create: `src/main/java/io/xac/systemd/cli/LogsCommand.java`
- Modify: `src/main/java/io/xac/systemd/cli/RootCommand.java`（subcommands 加 `LogsCommand.class`）
- Test: `src/test/java/io/xac/systemd/logs/LogsRunnerTest.java`

**Interfaces:**
- Consumes: `ServiceSpec`、`CommandRunner`、`UnitNaming`
- Produces:
  - `LogsRunner.run(ServiceSpec spec, CommandRunner runner, int lines, boolean follow)` → `ExecResult`（follow 模式走 `runInheritIo` 并返回退出码 0 的结果；非 follow 模式返回 `journalctl` 的 stdout 供调用方打印）

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.logs;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsRunnerTest {

    private ServiceSpec spec() {
        return ServiceSpec.builder(Paths.get("/opt/demo"))
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    @Test
    void readsJournalWithNoPager() {
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("journalctl", new ExecResult(0, "line1\nline2\n", ""));

        ExecResult result = new LogsRunner().run(spec(), runner, 100, false);

        assertEquals("line1\nline2\n", result.stdout);
        assertEquals(Arrays.asList("journalctl", "-u", "demo.service", "--no-pager", "-n", "100"),
                runner.calls().get(0));
        assertTrue(runner.inheritIoCalls().isEmpty());
    }

    @Test
    void followsWithInheritIo() {
        RecordingRunner runner = new RecordingRunner();
        new LogsRunner().run(spec(), runner, 50, true);

        assertEquals(Arrays.asList("journalctl", "-u", "demo.service", "--no-pager", "-n", "50",
                "--follow"), runner.inheritIoCalls().get(0));
        assertTrue(runner.calls().isEmpty());
    }

    @Test
    void rejectsNonPositiveLineCount() {
        RecordingRunner runner = new RecordingRunner();
        ExecResult result = new LogsRunner().run(spec(), runner, 0, false);
        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.contains("-n"), result.stderr);
        assertTrue(runner.calls().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=LogsRunnerTest`
Expected: `COMPILATION ERROR`：找不到 `LogsRunner`。

- [ ] **Step 3: 实现 logs**

`logs/LogsRunner.java`：

```java
package io.xac.systemd.logs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.UnitNaming;

public final class LogsRunner {

    public ExecResult run(ServiceSpec spec, CommandRunner runner, int lines, boolean follow) {
        if (lines <= 0) {
            return new ExecResult(2, "", "-n 必须是正整数");
        }
        List<String> argv = new ArrayList<String>(Arrays.asList(
                "journalctl", "-u", UnitNaming.unitName(spec), "--no-pager",
                "-n", String.valueOf(lines)));
        if (follow) {
            argv.add("--follow");
            runner.runInheritIo(argv);
            return new ExecResult(0, "", "");
        }
        return runner.run(argv);
    }
}
```

`cli/LogsCommand.java`：

```java
package io.xac.systemd.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.logs.LogsRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "logs", description = "查看服务日志（journalctl）")
public final class LogsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Option(names = {"-n", "--lines"}, defaultValue = "200", description = "显示最近多少行（缺省 200）")
    int lines;

    @Option(names = {"-f", "--follow"}, description = "持续输出新日志")
    boolean follow;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.parse(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        ExecResult result = new LogsRunner().run(loaded.spec, new ProcessRunner(), lines, follow);
        if (!result.ok()) {
            System.err.println(result.stderr.trim());
        } else if (!follow) {
            System.out.print(result.stdout);
        }
        return result.ok() ? 0 : 2;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=LogsRunnerTest`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: logs 命令"
```

---

### Task 11: destroy 命令

**Files:**
- Modify: `src/main/java/io/xac/systemd/apply/ApplyContext.java`（加 `public final List<String> warnings`）
- Create: `src/main/java/io/xac/systemd/apply/actions/StopAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/DisableAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/RemoveUnitAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/RemoveEnvAction.java`
- Create: `src/main/java/io/xac/systemd/apply/actions/RemoveLinksAction.java`
- Create: `src/main/java/io/xac/systemd/apply/DestroyRunner.java`
- Create: `src/main/java/io/xac/systemd/cli/DestroyCommand.java`
- Modify: `src/main/java/io/xac/systemd/cli/RootCommand.java`（subcommands 加 `DestroyCommand.class`）
- Test: `src/test/java/io/xac/systemd/apply/DestroyRunnerTest.java`

**Interfaces:**
- Consumes: `ApplyContext`、`Action`、`ActionFailure`、`UnitNaming`、`PathLink`
- Produces:
  - `DestroyRunner.actions()` → `List<Action>`（固定 6 个：stop、disable、删 unit、删 env、daemon-reload、删 path-links）
  - `DestroyRunner.run(ApplyContext ctx, PrintStream out)` → `int`
  - `DestroyCommand`：`xac destroy [<package-dir>] [--dry-run] [--yes]`；非交互且没 `--yes` 时返回 2

- [ ] **Step 1: 写失败测试**

```java
package io.xac.systemd.apply;

import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.exec.RecordingRunner;
import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.systemd.SystemdTarget;
import io.xac.systemd.systemd.UnitNaming;
import io.xac.systemd.systemd.UnitRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyRunnerTest {

    @TempDir
    Path root;

    private SystemdTarget target() {
        return new SystemdTarget(root.resolve("units"), root.resolve("env"));
    }

    private ServiceSpec spec() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("BAR", "b");
        return ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .env(env)
                .lifecycle(new Lifecycle(true, null, null, null))
                .build();
    }

    private void install(ServiceSpec spec) throws IOException {
        Files.createDirectories(target().unitDir);
        Files.createDirectories(target().envDir);
        UnitRenderer renderer = new UnitRenderer();
        Files.write(UnitNaming.unitPath(spec, target()),
                renderer.render(spec, target()).getBytes(StandardCharsets.UTF_8));
        Files.write(UnitNaming.envPath(spec, target()),
                renderer.renderEnvFile(spec).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stopsDisablesRemovesThenReloads() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        int code = new DestroyRunner().run(
                new ApplyContext(spec, target(), runner, false),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertEquals(0, code);
        assertEquals(Arrays.asList(
                Arrays.asList("systemctl", "stop", "demo.service"),
                Arrays.asList("systemctl", "disable", "demo.service"),
                Arrays.asList("systemctl", "daemon-reload")), runner.calls());
        assertFalse(Files.exists(UnitNaming.unitPath(spec, target())));
        assertFalse(Files.exists(UnitNaming.envPath(spec, target())));
    }

    @Test
    void continuesWhenDisableFails() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();
        runner.addResultForCommand("systemctl", new ExecResult(1, "", "not loaded"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, false),
                new PrintStream(buffer, true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(buffer.toString("UTF-8").contains("警告"), buffer.toString("UTF-8"));
        assertFalse(Files.exists(UnitNaming.unitPath(spec, target())));
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        ServiceSpec spec = spec();
        install(spec);
        RecordingRunner runner = new RecordingRunner();

        int code = new DestroyRunner().run(new ApplyContext(spec, target(), runner, true),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertEquals(0, code);
        assertTrue(runner.calls().isEmpty());
        assertTrue(Files.exists(UnitNaming.unitPath(spec, target())));
    }

    @Test
    void removesLinkOnlyWhenItPointsToSource() throws IOException {
        Assumptions.assumeTrue(supportsSymlinks(root));
        Files.createDirectories(target().unitDir);
        Path source = Files.createDirectories(root.resolve("payload"));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, source);

        ServiceSpec spec = ServiceSpec.builder(root)
                .name("demo")
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .pathLinks(new ArrayList<String>(Arrays.asList(source + ":" + linked)))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();

        new DestroyRunner().run(new ApplyContext(spec, target(), new RecordingRunner(), false),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));

        assertFalse(Files.exists(linked));
        assertTrue(Files.isDirectory(source));
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("probe-link");
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=DestroyRunnerTest`
Expected: `COMPILATION ERROR`：找不到 `DestroyRunner`。

- [ ] **Step 3: 实现 destroy**

`ApplyContext` 增加警告通道（放在字段列表末尾）：

```java
    /** 动作可以忽略的失败（如 unit 未安装时的 stop）；由 Runner 打印后清空。 */
    public final java.util.List<String> warnings = new java.util.ArrayList<String>();
```

`actions/StopAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class StopAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl stop " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", "stop", UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            ctx.warnings.add("systemctl stop 退出码 " + result.exitCode + "（已忽略）");
        }
    }
}
```

`actions/DisableAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.util.Arrays;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.exec.ExecResult;
import io.xac.systemd.systemd.UnitNaming;

public final class DisableAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "systemctl disable " + UnitNaming.unitName(ctx.spec);
    }

    @Override
    public void run(ApplyContext ctx) {
        ExecResult result = ctx.runner.run(
                Arrays.asList("systemctl", "disable", UnitNaming.unitName(ctx.spec)));
        if (!result.ok()) {
            ctx.warnings.add("systemctl disable 退出码 " + result.exitCode + "（已忽略）");
        }
    }
}
```

`actions/RemoveUnitAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.systemd.UnitNaming;

public final class RemoveUnitAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "删除 " + UnitNaming.unitPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path unitPath = UnitNaming.unitPath(ctx.spec, ctx.target);
        try {
            Files.deleteIfExists(unitPath);
        } catch (IOException e) {
            throw new ActionFailure("删除 unit 文件失败 " + unitPath + ": " + e.getMessage(), e);
        }
    }
}
```

`actions/RemoveEnvAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.systemd.UnitNaming;

public final class RemoveEnvAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "删除 " + UnitNaming.envPath(ctx.spec, ctx.target);
    }

    @Override
    public void run(ApplyContext ctx) {
        Path envPath = UnitNaming.envPath(ctx.spec, ctx.target);
        try {
            Files.deleteIfExists(envPath);
        } catch (IOException e) {
            throw new ActionFailure("删除 env 文件失败 " + envPath + ": " + e.getMessage(), e);
        }
    }
}
```

`actions/RemoveLinksAction.java`：

```java
package io.xac.systemd.apply.actions;

import java.io.IOException;
import java.nio.file.Files;

import io.xac.systemd.apply.Action;
import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.spec.PathLink;

public final class RemoveLinksAction implements Action {

    @Override
    public String describe(ApplyContext ctx) {
        return "移除 path-links: " + ctx.spec.pathLinks;
    }

    @Override
    public void run(ApplyContext ctx) {
        for (String raw : ctx.spec.pathLinks) {
            PathLink link = PathLink.parse(raw, ctx.spec.packageDir);
            if (link == null) {
                continue;
            }
            try {
                // 只删自己建的链接；真实目录/文件一律不碰。
                if (Files.isSymbolicLink(link.target)
                        && Files.readSymbolicLink(link.target).equals(link.source)) {
                    Files.delete(link.target);
                }
            } catch (IOException e) {
                throw new ActionFailure("移除软链接失败 " + link.target + ": " + e.getMessage(), e);
            }
        }
    }
}
```

`DestroyRunner.java`：

```java
package io.xac.systemd.apply;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import io.xac.systemd.apply.actions.DaemonReloadAction;
import io.xac.systemd.apply.actions.DisableAction;
import io.xac.systemd.apply.actions.RemoveEnvAction;
import io.xac.systemd.apply.actions.RemoveLinksAction;
import io.xac.systemd.apply.actions.RemoveUnitAction;
import io.xac.systemd.apply.actions.StopAction;

public final class DestroyRunner {

    /** 顺序（设计文档 §6.5）：先停服务，再删文件，最后移除链接。 */
    public static List<Action> actions() {
        return Arrays.<Action>asList(
                new StopAction(),
                new DisableAction(),
                new RemoveUnitAction(),
                new RemoveEnvAction(),
                new DaemonReloadAction(),
                new RemoveLinksAction());
    }

    public int run(ApplyContext ctx, PrintStream out) {
        for (Action action : actions()) {
            String description = action.describe(ctx);
            if (ctx.dryRun) {
                out.println("would " + description);
                continue;
            }
            out.println(description);
            try {
                action.run(ctx);
            } catch (ActionFailure e) {
                out.println("失败: " + e.getMessage());
                return 1;
            }
            for (String warning : ctx.warnings) {
                out.println("警告: " + warning);
            }
            ctx.warnings.clear();
        }
        return 0;
    }
}
```

`cli/DestroyCommand.java`：

```java
package io.xac.systemd.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ApplyContext;
import io.xac.systemd.apply.DestroyRunner;
import io.xac.systemd.exec.ProcessRunner;
import io.xac.systemd.systemd.SystemdTarget;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "destroy", description = "停止并移除服务（unit、env 文件与 path-links）")
public final class DestroyCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            description = "包目录，必须包含 xac.yaml（缺省当前目录）")
    String packageDirArg;

    @Option(names = "--dry-run", description = "只打印将执行的动作，不做任何改动")
    boolean dryRun;

    @Option(names = {"-y", "--yes"}, description = "跳过确认")
    boolean yes;

    @Override
    public Integer call() throws IOException {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        SpecCommands.Loaded loaded = SpecCommands.load(packageDir);
        if (loaded.spec == null) {
            SpecCommands.report(loaded.violations);
            return 2;
        }
        if (!yes && !dryRun && !confirm(loaded.spec.name)) {
            return 2;
        }
        ApplyContext ctx = new ApplyContext(loaded.spec, SystemdTarget.defaults(),
                new ProcessRunner(), dryRun);
        return new DestroyRunner().run(ctx, System.out);
    }

    private boolean confirm(String name) throws IOException {
        Console console = System.console();
        if (console == null) {
            System.err.println("非交互环境请加 --yes");
            return false;
        }
        String answer = console.readLine("确认删除 %s 的 unit 与 env 文件？[y/N] ", name);
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -f java-systemd-service-as-code/pom.xml test -Dtest=DestroyRunnerTest`
Expected: `Tests run: 4, Failures: 0`（Windows 无软链接权限时第 4 个 skipped）

- [ ] **Step 5: 提交**

```bash
git add java-systemd-service-as-code/src
git commit -m "feat: destroy 命令"
```

---

### Task 12: fat jar 与容器内端到端验证

**Files:**
- Create: `java-systemd-service-as-code/e2e/sample/Demo.java`
- Create: `java-systemd-service-as-code/e2e/smoke.sh`
- Modify: `java-systemd-service-as-code/e2e/README.md`（补 smoke.sh 用法）

**Interfaces:**
- Consumes: 全部命令
- Produces: `target/java-systemd-service-cli-0.1.0-SNAPSHOT.jar`（fat jar，`Main-Class: io.xac.systemd.cli.Main`）

- [ ] **Step 1: 写样例服务与冒烟脚本**

`e2e/sample/Demo.java`（只用 Java 8 API）：

```java
import java.lang.management.ManagementFactory;

public final class Demo {

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("STOPPING");
            }
        }));
        System.out.println("READY jvm=" + ManagementFactory.getRuntimeMXBean().getName());
        System.out.println("GREETING=" + System.getenv("GREETING"));
        System.out.println("CWD=" + System.getProperty("user.dir"));
        System.out.println("XMX=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
        while (true) {
            Thread.sleep(1000L);
        }
    }

    private Demo() {
    }
}
```

`e2e/smoke.sh`：

```bash
#!/usr/bin/env bash
# 在 Ubuntu 24.04 + systemd 255 容器里跑一遍 apply / status / destroy。
# 前提：本机有 docker，且已能 `docker build` 过 e2e/Dockerfile。
set -euo pipefail

cd "$(dirname "$0")/.."
IMAGE="${IMAGE:-xac-e2e:24.04}"
CONTAINER="xac-smoke"
WORK="$(mktemp -d)"
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

echo "== build =="
mvn -q package
JAR="target/java-systemd-service-cli-0.1.0-SNAPSHOT.jar"

echo "== sample package =="
mkdir -p "$WORK/classes"
javac -d "$WORK/classes" --release 8 e2e/sample/Demo.java
jar cfe "$WORK/demo.jar" Demo -C "$WORK/classes" .
cp "$WORK/demo.jar" "$WORK/app.jar"
cat > "$WORK/xac.yaml" <<'YAML'
name: demo
description: xac 冒烟服务
artifact:
  jar: app.jar
runtime:
  java: /usr/bin/java
  opts:
    - -Xmx256m
env:
  GREETING: hello world
lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 2
  timeout-stop-sec: 10
YAML

echo "== container =="
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" --privileged --cgroupns=host \
  --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
  --entrypoint /sbin/init "$IMAGE" >/dev/null
for _ in $(seq 1 30); do
  if docker exec "$CONTAINER" systemctl is-system-running >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$CONTAINER" mkdir -p /opt/demo
docker cp "$WORK/." "$CONTAINER:/opt/demo"
docker cp "$JAR" "$CONTAINER:/opt/xac.jar"

echo "== apply =="
docker exec "$CONTAINER" java -jar /opt/xac.jar apply /opt/demo | tee "$WORK/apply.out"
grep -q "systemctl restart demo.service" "$WORK/apply.out"
test "$(docker exec "$CONTAINER" systemctl is-active demo.service)" = "active"

echo "== env 生效 =="
docker exec "$CONTAINER" bash -lc 'journalctl -u demo.service --no-pager | grep "GREETING=hello world"'
docker exec "$CONTAINER" bash -lc 'systemctl show demo.service --property=UnitFileState | grep -q enabled'

echo "== status =="
docker exec "$CONTAINER" java -jar /opt/xac.jar status /opt/demo | tee "$WORK/status.out"
grep -q "(in sync)" "$WORK/status.out"
grep -q "auto-start: enabled" "$WORK/status.out"

echo "== stop 后状态（SuccessExitStatus=143）=="
docker exec "$CONTAINER" systemctl start demo.service
docker exec "$CONTAINER" systemctl stop demo.service
test "$(docker exec "$CONTAINER" systemctl show demo.service --property=ActiveState | cut -d= -f2)" = "inactive"

echo "== destroy =="
docker exec "$CONTAINER" java -jar /opt/xac.jar destroy /opt/demo --yes | tee "$WORK/destroy.out"
test ! -e "/dev/null"
docker exec "$CONTAINER" bash -lc 'test ! -e /etc/systemd/system/demo.service'
docker exec "$CONTAINER" bash -lc 'test ! -e /etc/xac/demo.env'
docker exec "$CONTAINER" bash -lc 'systemctl show demo.service --property=LoadState | grep -q not-found'

echo "SMOKE OK"
```

- [ ] **Step 2: 构建 fat jar 并确认入口**

Run:
```powershell
mvn -q -f java-systemd-service-as-code/pom.xml package
java -jar java-systemd-service-as-code/target/java-systemd-service-cli-0.1.0-SNAPSHOT.jar --help
```
Expected: 打印 usage，退出码 0；usage 里能看到 `apply`、`status`、`logs`、`destroy`。

- [ ] **Step 3: 构建镜像并跑冒烟**

Run:
```bash
cd java-systemd-service-as-code
docker build -t xac-e2e:24.04 e2e
bash e2e/smoke.sh
```
Expected: 最后一行 `SMOKE OK`。

若 `apply` 后服务不是 `active`：进入容器 `journalctl -u demo.service --no-pager`，优先怀疑 `ExecStart` 里 jar 路径或 `EnvironmentFile` 引号问题。

- [ ] **Step 4: 提交**

```bash
git add java-systemd-service-as-code/e2e
git commit -m "test: 容器内 apply/status/destroy 冒烟"
```

---

## Self-Review

**1. Spec 覆盖（设计文档 §6.1 的 13 条校验规则）**

| 规则 | 落点 |
|---|---|
| 必填齐备 | `Schema` 的 `required` + Task 2 测试 `reportsMissingRequiredFields` |
| 无未知字段 / 类型正确（报精确路径） | `Schema.check` + `SpecLoader` 路径拼接 + Task 2 两个测试 |
| `name` 字符集 | `NameValidator`（Task 3） |
| `env` 键名 / 无换行 / 不重复 | `EnvValidator`（键名、换行）+ `LoaderOptions.setAllowDuplicateKeys(false)`（重复） |
| `path-links` 切分与绝对路径 | `PathLink.parse` + `PathLinksValidator` |
| `${}` 都能在 env 找到 | `PlaceholderValidator` |
| jar 存在 | `Preflight` |
| java 存在且可执行 | `Preflight` |
| 是 root | `Preflight` |
| systemd 在跑 | `Preflight` |
| 堆大小不再校验 | 设计文档 D12 已删除该规则；计划中无对应代码（有意） |

四个命令的行为、退出码、`--dry-run` 分别落在 Task 7/8（apply）、9（status）、10（logs）、11（destroy）。

**2. 占位符扫描**

计划中每个 Task 都有可执行测试与实现代码；提交信息已写死。已知无 `TODO` / `TBD` 字样。

**3. 类型一致性**

- `Action.describe(ApplyContext)` / `run(ApplyContext)`：Task 6 定义，Task 7、11 使用。
- `ApplyContext(spec, target, runner, dryRun)`：Task 6 定义，Task 7/8/11 使用。
- `SpecCommands.parse` / `load`：Task 8 引入 `load`，Task 9 拆出 `parse` 并让 `load` 复用之。
- `StatusResult` 构造参数在 Task 9 内自洽（`unitInstalled` / `envConfigured` 由 `StatusRunner` 填）。
- `LogsRunner.run` 返回 `ExecResult`，`LogsCommand` 负责打印 stdout。
- `ApplyContext.warnings` 在 Task 11 引入，仅 `StopAction` / `DisableAction` 写入。

**4. 与设计文档的已知偏差（有意，需在实现时保持不变）**

- 设计文档 §3 提到「一条 unit 指令一个类」；计划用 `Directive` 匿名实现列表实现同一目的（顺序显式、可追加），不额外拆 9 个文件。
- env 文件的值统一写成 `KEY="值"`（转义 `\` 与 `"`），而不是裸值。原因：值可能含首尾空格或引号，加引号让 systemd 的解析无歧义；Task 12 冒烟会用 `GREETING=hello world` 验证。

---

## Execution Handoff

计划完成。两种执行方式：

1. **Subagent-Driven（推荐）**：每个 Task 派一个子代理执行，Task 之间做代码评审，上下文干净。
2. **Inline**：在当前会话里按 Task 顺序执行，每完成一个 Task 停下来确认。

选定后开始 Task 1。
