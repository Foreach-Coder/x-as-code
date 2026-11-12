package io.xac.systemd.e2e;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 冒烟测试里被 systemd 拉起的示例进程。
 *
 * <p>它把 JVM 实际收到的参数逐行打印（每条用方括号包住），这样可以从 journal 里
 * 直接观察到 systemd 的占位符展开、{@code %%} / {@code $$} 转义以及 -Xmx 是否生效；
 * 随后进入睡眠，等待 systemd 停止，停止时由 shutdown hook 打印 STOPPING。</p>
 *
 * <p>指定 {@code -Dlog.file=<path>} 时，它还会把 {@code FILELOG-*} 行写进那个文件，
 * 用来验证 {@code logs.file} 能从文件读日志——这些行不会出现在 stdout/journal 里。</p>
 */
public final class DemoApp {

    private DemoApp() {
    }

    public static void main(String[] args) throws Exception {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            System.out.println("[" + arg + "]");
        }
        for (String arg : args) {
            System.out.println("{" + arg + "}");
        }
        System.out.println("READY pid=" + currentPid());
        System.out.println("GREETING=" + System.getenv("GREETING"));
        System.out.println("CWD=" + System.getProperty("user.dir"));
        System.out.println("XMX=" + Runtime.getRuntime().maxMemory());
        System.out.flush();

        final FileWriter logWriter = openLogFile(System.getProperty("log.file"));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("STOPPING");
                System.out.flush();
                if (logWriter != null) {
                    try {
                        logWriter.write("FILELOG-STOPPING\n");
                        logWriter.flush();
                        logWriter.close();
                    } catch (IOException e) {
                        // 进程正在退出，日志文件写不了也不影响测试结论。
                    }
                }
            }
        }));

        int tick = 0;
        while (true) {
            Thread.sleep(1000L);
            if (logWriter != null) {
                try {
                    logWriter.write("FILELOG-TICK n=" + tick + "\n");
                    logWriter.flush();
                } catch (IOException e) {
                    // 轮转或权限变化时忽略，进程继续跑。
                }
                tick++;
            }
        }
    }

    private static FileWriter openLogFile(String path) throws IOException {
        if (path == null) {
            return null;
        }
        FileWriter writer = new FileWriter(path, true);
        writer.write("FILELOG-READY\n");
        writer.flush();
        return writer;
    }

    private static String currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }
}
