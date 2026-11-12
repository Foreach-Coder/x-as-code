package io.xac.systemd.cli;

/** 读取一行交互输入；返回 null 表示输入结束（EOF）。 */
interface Prompter {

    String readLine(String prompt);
}
