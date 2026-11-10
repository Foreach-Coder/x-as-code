package io.xac.systemd.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecordingRunner implements CommandRunner {

    private final List<List<String>> calls = new ArrayList<List<String>>();
    private final Map<List<String>, ExecResult> byArgv = new HashMap<List<String>, ExecResult>();
    private final Map<String, ExecResult> byCommand = new HashMap<String, ExecResult>();
    private final List<List<String>> inheritIo = new ArrayList<List<String>>();

    /** 为某一条完整 argv 预置返回值；优先于按可执行文件名的匹配。 */
    public void addResult(List<String> argv, ExecResult result) {
        byArgv.put(new ArrayList<String>(argv), result);
    }

    /** 为某个可执行文件（argv 的第一个元素）预置返回值。 */
    public void addResultForCommand(String executable, ExecResult result) {
        byCommand.put(executable, result);
    }

    /** 只记录 {@code run} 的调用；继承 stdio 的调用见 {@link #inheritIoCalls()}。 */
    public List<List<String>> calls() {
        return calls;
    }

    public List<List<String>> inheritIoCalls() {
        return inheritIo;
    }

    @Override
    public ExecResult run(List<String> argv) {
        calls.add(new ArrayList<String>(argv));
        ExecResult result = byArgv.get(argv);
        if (result == null) {
            result = byCommand.get(argv.get(0));
        }
        return result == null ? new ExecResult(0, "", "") : result;
    }

    @Override
    public void runInheritIo(List<String> argv) {
        inheritIo.add(new ArrayList<String>(argv));
    }
}
