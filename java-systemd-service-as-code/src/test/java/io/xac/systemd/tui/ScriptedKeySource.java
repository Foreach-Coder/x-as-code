package io.xac.systemd.tui;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

/**
 * 测试用 KeySource：按脚本顺序吐出按键，吐完返回 null，永不阻塞。
 */
final class ScriptedKeySource implements KeySource {

    private final Deque<KeyEvent> events = new ArrayDeque<KeyEvent>();
    private int polls;

    ScriptedKeySource(KeyEvent... events) {
        Collections.addAll(this.events, events);
    }

    @Override
    public KeyEvent next(long timeoutMillis) {
        polls++;
        return events.poll();
    }

    int polls() {
        return polls;
    }
}
