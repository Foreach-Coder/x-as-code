package io.xac.systemd.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class RecordingRunner implements CommandRunner {

    private final List<List<String>> calls = new ArrayList<List<String>>();
    private final Map<List<String>, ExecResult> byArgv = new HashMap<List<String>, ExecResult>();
    private final Map<String, ExecResult> byCommand = new HashMap<String, ExecResult>();
    private final List<List<String>> inheritIo = new ArrayList<List<String>>();
    private final List<List<String>> started = new ArrayList<List<String>>();
    private final Map<List<String>, StartedScript> startedByArgv =
            new HashMap<List<String>, StartedScript>();

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

    /** 只记录 {@code start} 的调用；{@code run} 的调用见 {@link #calls()}。 */
    public List<List<String>> startedCalls() {
        return started;
    }

    /**
     * 为某一条完整 argv 预置 {@link #start} 的脚本化输出，退出码固定为 0。
     *
     * @param blockUntilDestroyed {@code true} 表示 stdout 读完预置文本后继续阻塞，
     *                            直到 {@link RunningCommand#destroy()} 才返回 EOF，
     *                            用于模拟 {@code logs --follow} 这类不会自己结束的命令
     */
    public void addStartedOutput(List<String> argv, String stdout, String stderr,
                                 boolean blockUntilDestroyed) {
        startedByArgv.put(new ArrayList<String>(argv),
                new StartedScript(stdout, stderr, blockUntilDestroyed));
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

    @Override
    public RunningCommand start(List<String> argv) {
        started.add(new ArrayList<String>(argv));
        StartedScript script = startedByArgv.get(argv);
        if (script == null) {
            script = new StartedScript("", "", false);
        }
        return new ScriptedCommand(script);
    }

    /** 一条脚本化的 {@code start}：预置输出、可选阻塞，destroy() 后结束。 */
    private static final class StartedScript {

        final String stdout;
        final String stderr;
        final boolean blockUntilDestroyed;
        final int exitCode;

        StartedScript(String stdout, String stderr, boolean blockUntilDestroyed) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.blockUntilDestroyed = blockUntilDestroyed;
            this.exitCode = 0;
        }
    }

    private static final class ScriptedCommand implements RunningCommand {

        private final StartedScript script;
        private final CountDownLatch release = new CountDownLatch(1);
        private final ScriptedStream stdout;
        private final ScriptedStream stderr;
        private boolean destroyed;

        ScriptedCommand(StartedScript script) {
            this.script = script;
            this.stdout = new ScriptedStream(script.stdout, script.blockUntilDestroyed ? release : null);
            this.stderr = new ScriptedStream(script.stderr, null);
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return script.exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
            release.countDown();
        }

        @Override
        public void destroyForcibly() {
            destroy();
        }

        @Override
        public boolean isAlive() {
            return !destroyed && (script.blockUntilDestroyed || !stdout.eofReached());
        }
    }

    /**
     * 预置文本构成的输入流。{@code release} 为 null 时读不到更多字节就是 EOF；
     * 否则预置文本读完后阻塞等待 {@code release}，模拟一个仍在运行的子进程。
     */
    private static final class ScriptedStream extends InputStream {

        private final byte[] bytes;
        private final CountDownLatch release;
        private int index;
        private boolean eof;

        ScriptedStream(String text, CountDownLatch release) {
            this.bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            this.release = release;
        }

        @Override
        public int read() throws IOException {
            if (index < bytes.length) {
                return bytes[index++] & 0xff;
            }
            if (release != null && !eof) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for destroy()", e);
                }
            }
            eof = true;
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (index >= bytes.length) {
                return read();
            }
            int count = Math.min(length, bytes.length - index);
            System.arraycopy(bytes, index, buffer, offset, count);
            index += count;
            return count;
        }

        boolean eofReached() {
            return eof;
        }
    }
}
