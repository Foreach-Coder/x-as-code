package io.xac.systemd.exec;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试夹具：拼装“用当前 JVM 跑一个测试类”的 argv，并提供几个可复用的子进程场景。
 *
 * <p>surefire 默认以 manifest-only booter jar 启动 fork，此时 {@code java.class.path}
 * 只剩 booter jar，子进程无法加载测试类；因此优先用测试类自身的 code source
 * （target/test-classes）并附带 java.class.path。</p>
 *
 * <p>不属于生产代码，仅供测试使用。</p>
 */
public final class ExecFixtures {

    private ExecFixtures() {
    }

    public static String javaExe() {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        return java.toString();
    }

    /**
     * 子进程可用的 classpath：测试类自身的 code source 在前，{@code java.class.path} 在后。
     */
    public static String classpath() {
        String codeSource = null;
        try {
            java.net.URL location = ExecFixtures.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equals(location.getProtocol())) {
                codeSource = Paths.get(location.toURI()).toString();
            }
        } catch (URISyntaxException e) {
            codeSource = null;
        }
        String javaClassPath = System.getProperty("java.class.path");
        if (codeSource == null) {
            return javaClassPath;
        }
        return codeSource + File.pathSeparator + javaClassPath;
    }

    /** {@code java -cp <fixture classpath> <mainClass> <args...>} 的 argv。 */
    public static List<String> java(String mainClass, String... args) {
        List<String> argv = new ArrayList<String>();
        argv.add(javaExe());
        argv.add("-cp");
        argv.add(classpath());
        argv.add(mainClass);
        argv.addAll(Arrays.asList(args));
        return argv;
    }

    /** 依次打印 A、B、C 后正常退出（exit 0）。 */
    public static final class LinePrinter {

        public static void main(String[] args) {
            System.out.println("A");
            System.out.println("B");
            System.out.println("C");
            System.out.flush();
        }
    }

    /** 打印 READY 后阻塞 60 秒；用于验证 destroy() 能终止仍在运行的子进程。 */
    public static final class Waiter {

        public static void main(String[] args) throws InterruptedException {
            PrintThenWait.main(new String[] {"READY"});
        }
    }
}
