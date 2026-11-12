package io.xac.systemd.tui;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import io.xac.systemd.core.InvalidSpecException;
import io.xac.systemd.core.ServicePackage;
import io.xac.systemd.spec.Violation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tui} 子命令：只在交互式终端里启动，否则一行提示后退出 2，
 * 保证 CI / 重定向输出时不会卡在终端初始化上。
 */
@Command(name = "tui",
        mixinStandardHelpOptions = true,
        description = "interactive terminal UI (Linux/SSH terminal; exits 2 without a TTY)")
public final class TuiCommand implements Callable<Integer> {

    static final String NON_TTY_MESSAGE = "tui requires an interactive terminal";

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            paramLabel = "<package-dir>",
            description = "package directory containing xac.yaml (default: current directory)")
    String packageDirArg;

    @Override
    public Integer call() {
        Path packageDir = Paths.get(packageDirArg).toAbsolutePath().normalize();
        boolean interactive = isInteractiveTerminal(
                System.console() != null, System.getenv("TERM"));
        return run(packageDir, interactive, System.err, new LanternaLauncher(System.err));
    }

    static int run(Path packageDir, boolean interactive, PrintStream err, Launcher launcher) {
        if (!interactive) {
            err.println(NON_TTY_MESSAGE);
            return 2;
        }
        ServicePackage servicePackage;
        try {
            servicePackage = ServicePackage.open(packageDir);
        } catch (InvalidSpecException e) {
            for (Violation violation : e.violations) {
                err.println(violation);
            }
            return 2;
        }
        return launcher.launch(servicePackage);
    }

    /**
     * 同时要求：进程挂着控制台，且 TERM 非空。任一不满足都按非 TTY 处理，
     * 不尝试打开终端。
     */
    static boolean isInteractiveTerminal(boolean consoleAttached, String term) {
        return consoleAttached && term != null && !term.trim().isEmpty();
    }

    interface Launcher {
        int launch(ServicePackage servicePackage);
    }

    private static final class LanternaLauncher implements Launcher {

        private final PrintStream err;

        LanternaLauncher(PrintStream err) {
            this.err = err;
        }

        @Override
        public int launch(ServicePackage servicePackage) {
            LanternaDisplay display = null;
            try {
                display = LanternaDisplay.open();
                new TuiApp(servicePackage, display, display.keys()).run();
                return 0;
            } catch (IOException e) {
                err.println("tui failed: " + e.getMessage());
                return 1;
            } finally {
                if (display != null) {
                    display.close();
                }
            }
        }
    }
}
