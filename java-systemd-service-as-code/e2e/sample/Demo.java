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
