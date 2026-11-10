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
