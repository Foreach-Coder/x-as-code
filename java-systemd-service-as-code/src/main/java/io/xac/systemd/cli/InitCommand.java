package io.xac.systemd.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.apply.ActionFailure;
import io.xac.systemd.apply.FileWriter;
import io.xac.systemd.spec.Violation;
import io.xac.systemd.spec.validators.NameValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 交互式创建项目目录：提问三个字段，在 {@code <parent>/<name>/xac.yaml} 写出带注释的模板。
 * 已存在的 xac.yaml、非空目录、非法服务名都会重新提问，不覆盖任何文件。
 */
@Command(mixinStandardHelpOptions = true, name = "init",
        description = "create a service project directory with a commented xac.yaml")
public final class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "<parent-dir>",
            description = "parent directory for the new project (default: current directory)")
    String parentDirArg;

    @Override
    public Integer call() {
        return run(parentDirArg, new ConsolePrompter(), System.out, System.err);
    }

    static int run(String parentDirArg, Prompter prompter, PrintStream out, PrintStream err) {
        Path parent = Paths.get(parentDirArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(parent)) {
            err.println("error: parent directory does not exist: " + parent);
            return 2;
        }

        String name;
        Path target;
        while (true) {
            name = ask(prompter, err, "service name", "demo");
            if (name == null) {
                return 2;
            }
            Violation violation = NameValidator.violationFor(name);
            if (violation != null) {
                err.println("error: invalid service name: " + violation.message);
                continue;
            }
            target = parent.resolve(name).normalize();
            Path yaml = target.resolve("xac.yaml");
            if (Files.exists(yaml)) {
                err.println("error: already exists: " + yaml + " (choose another name)");
                continue;
            }
            if (Files.isDirectory(target) && !isEmpty(target)) {
                err.println("error: project directory is not empty: " + target
                        + " (choose another name)");
                continue;
            }
            if (Files.exists(target) && !Files.isDirectory(target)) {
                err.println("error: not a directory: " + target + " (choose another name)");
                continue;
            }
            break;
        }

        String jar = ask(prompter, err, "artifact jar", "app.jar");
        if (jar == null) {
            return 2;
        }
        String java = ask(prompter, err, "java executable", "/usr/bin/java");
        if (java == null) {
            return 2;
        }

        Path yaml = target.resolve("xac.yaml");
        String content;
        try {
            content = readTemplate()
                    .replace("{{name}}", name)
                    .replace("{{jar}}", jar)
                    .replace("{{java}}", java);
            Files.createDirectories(target);
            FileWriter.write(yaml, content, "rw-r--r--");
        } catch (IOException e) {
            err.println("error: cannot write " + yaml + ": " + e.getMessage());
            return 1;
        } catch (ActionFailure e) {
            err.println("error: cannot write " + yaml + ": " + e.getMessage());
            return 1;
        }

        out.println("created " + yaml);
        out.println("next: edit it, then run: java-systemd-service-cli apply " + target + " --dry-run");
        return 0;
    }

    private static String ask(Prompter prompter, PrintStream err, String label, String defaultValue) {
        String answer = prompter.readLine(label + " [" + defaultValue + "]: ");
        if (answer == null) {
            err.println("error: no input for " + label);
            return null;
        }
        String trimmed = answer.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private static boolean isEmpty(Path dir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static String readTemplate() throws IOException {
        try (InputStream in = InitCommand.class.getResourceAsStream("/xac.yaml.template")) {
            if (in == null) {
                throw new IOException("template not found on classpath: /xac.yaml.template");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
