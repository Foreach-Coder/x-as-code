package io.xac.systemd.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 终端交互：有 Console 时用它（提示不进入 stdout，便于重定向），
 * 否则从 System.in 读，支持管道输入。
 */
final class ConsolePrompter implements Prompter {

    private final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

    @Override
    public String readLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("%s", prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            return stdin.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
