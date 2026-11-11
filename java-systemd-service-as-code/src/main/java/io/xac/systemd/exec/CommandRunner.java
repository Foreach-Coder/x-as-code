package io.xac.systemd.exec;

import java.util.List;

public interface CommandRunner {

    ExecResult run(List<String> argv);

    void runInheritIo(List<String> argv);
}
