package com.javaclaw.nativehost.ffm;

import java.util.List;

/** 按 CommandLineToArgvW 逆规则构造可逆命令行，不经过 cmd.exe。 */
final class WindowsCommandLine {
    private WindowsCommandLine() {}

    static String encode(List<String> arguments) {
        return arguments.stream().map(WindowsCommandLine::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    static String quote(String value) {
        if (!value.isEmpty()
                && value.chars().noneMatch(character -> Character.isWhitespace(character) || character == '"')) {
            return value;
        }
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                slashes++;
            } else if (character == '"') {
                result.append("\\".repeat(slashes * 2 + 1)).append('"');
                slashes = 0;
            } else {
                result.append("\\".repeat(slashes)).append(character);
                slashes = 0;
            }
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }
}
