package com.javaclaw.nativehost.ffm;

import java.nio.file.Path;
import java.util.List;

/** Windows 进程命令行编码；普通 argv 使用 CRT 规则，固定 cmd 调用保留其命令正文。 */
public final class WindowsCommandLine {
    private static final int MAXIMUM_PROCESS_UNITS = 32_766;
    private static final int MAXIMUM_CMD_UNITS = 8191;

    private WindowsCommandLine() {}

    /**
     * 校验传给 Java helper 的普通 argv 在 Windows Unicode 进程限制内。
     *
     * @param arguments 将由 ProcessBuilder 传输的完整参数列表
     * @throws IllegalArgumentException 转义后的 UTF-16 单元超过上限；不得截断参数
     */
    public static void requireTransportFits(List<String> arguments) {
        encode(arguments);
    }

    static String encode(List<String> arguments) {
        String result =
                arguments.stream().map(WindowsCommandLine::quote).collect(java.util.stream.Collectors.joining(" "));
        return bounded(result, MAXIMUM_PROCESS_UNITS);
    }

    static String encodeTarget(List<String> arguments, Path executable) {
        if (fixedShell(arguments, executable)) {
            // /s 只去掉外围这一对引号；正文内的引号、反斜杠和元字符保持原文，不使用 CRT 转义。
            return bounded(
                    quote(arguments.getFirst()) + " /d /s /c \"" + arguments.getLast() + "\"", MAXIMUM_CMD_UNITS);
        }
        return encode(arguments);
    }

    private static boolean fixedShell(List<String> arguments, Path executable) {
        Path shell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                .resolve("System32")
                .resolve("cmd.exe")
                .toAbsolutePath()
                .normalize();
        return executable.toAbsolutePath().normalize().equals(shell)
                && arguments.size() == 5
                && arguments.get(1).equalsIgnoreCase("/d")
                && arguments.get(2).equalsIgnoreCase("/s")
                && arguments.get(3).equalsIgnoreCase("/c");
    }

    private static String bounded(String encoded, int maximum) {
        if (encoded.length() > maximum) {
            throw new IllegalArgumentException("Windows encoded command line exceeds " + maximum + " UTF-16 units");
        }
        return encoded;
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
