package io.xac.systemd.e2e;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 冒烟测试里被 systemd 拉起的示例进程。
 *
 * <p>它把 JVM 实际收到的参数逐行打印（每条用方括号包住），这样可以从 journal 里
 * 直接观察到 systemd 的占位符展开、{@code %%} / {@code $$} 转义以及 -Xmx 是否生效；
 * 随后进入睡眠，等待 systemd 停止，停止时由 shutdown hook 打印 STOPPING。</p>
 */
public final class DemoApp {

    private DemoApp() {
    }

    public static void main(String[] args) throws InterruptedException {
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

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("STOPPING");
                System.out.flush();
            }
        }));

        while (true) {
            Thread.sleep(1000L);
        }
    }

    private static String currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }
}
