package io.xac.systemd.exec;

import java.util.List;

public interface CommandRunner {

    ExecResult run(List<String> argv);

    void runInheritIo(List<String> argv);

    /**
     * 启动子进程并立即返回，输出由调用方按需读取（流式，不缓冲、不排空）。
     * 与 {@link #run} 的“跑完再取输出”互补，用于 {@code logs --follow} 这类长驻命令。
     */
    RunningCommand start(List<String> argv);
}
