package com.javaclaw.client.cli;

import java.io.Reader;
import java.util.Objects;

/**
 * 终端输入来源；非 TTY 模式不读取重定向的 stdin，防止管道文本成为授权。
 *
 * @param interactive 是否存在可交互终端；无单位
 * @param reader 宿主拥有的字符读取器，不可空；非交互模式使用空读取器且不会读取
 */
record CliTerminal(boolean interactive, Reader reader) {
    CliTerminal {
        Objects.requireNonNull(reader, "reader");
    }

    static CliTerminal system() {
        java.io.Console console = System.console();
        return console == null ? new CliTerminal(false, Reader.nullReader()) : new CliTerminal(true, console.reader());
    }
}
