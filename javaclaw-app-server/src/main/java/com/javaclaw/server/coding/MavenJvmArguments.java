package com.javaclaw.server.coding;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析固定 Maven 启动器支持的可移植 JVM 参数；不执行 shell 展开或读取宿主环境。 */
final class MavenJvmArguments {
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--add-opens",
            "--add-exports",
            "--add-reads",
            "--add-modules",
            "--limit-modules",
            "--enable-native-access");
    private static final Set<String> LAUNCH_OPTIONS = Set.of(
            "-cp",
            "-classpath",
            "--class-path",
            "-jar",
            "-m",
            "--module",
            "--module-path",
            "-p",
            "-version",
            "--version",
            "-help",
            "--help",
            "-?",
            "--dry-run",
            "--",
            "-XX:Flags",
            "-XX:VMOptionsFile");

    private MavenJvmArguments() {}

    static List<String> parse(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> arguments = List.of(text.strip().split("[ \\t\\r\\n]+"));
        if (arguments.size() > 128) {
            throw unsupported("JVM 参数超过 128 项");
        }
        boolean value = false;
        for (String argument : arguments) {
            requirePortable(argument);
            if (value) {
                if (argument.startsWith("-")) {
                    throw unsupported("JVM 选项缺少独立参数");
                }
                value = false;
            } else {
                requireOption(argument);
                value = VALUE_OPTIONS.contains(argument);
            }
        }
        if (value) {
            throw unsupported("JVM 选项缺少独立参数");
        }
        return List.copyOf(arguments);
    }

    static void requireProperties(List<String> arguments, Map<String, String> governed) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            String property = argument.startsWith("-D") ? argument.substring(2) : "";
            if (argument.equals("-D") && index + 1 < arguments.size()) {
                property = arguments.get(++index);
            }
            int separator = property.indexOf('=');
            String key = separator < 0 ? property : property.substring(0, separator);
            String value = separator < 0 ? "" : property.substring(separator + 1);
            if (governed.containsKey(key) && !governed.get(key).equals(value)) {
                throw new IllegalArgumentException("MAVEN_JVM_PROPERTY_CONFLICT: " + key);
            }
        }
    }

    static List<String> prepend(List<String> original, List<String> arguments) {
        ArrayList<String> result = new ArrayList<>(original);
        result.addAll(1, arguments);
        return result;
    }

    private static void requirePortable(String argument) {
        if (argument.length() > 4096
                || argument.chars().anyMatch(character -> character < 32 || character == 127)
                || argument.chars().anyMatch(character -> "\"'*?[]".indexOf(character) >= 0)) {
            throw unsupported("仅支持 UTF-8 空白分隔参数，不支持引号、通配符或控制字符");
        }
    }

    private static void requireOption(String argument) {
        String option = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
        if (!argument.startsWith("-") || LAUNCH_OPTIONS.contains(option)) {
            throw unsupported("项目配置不得替换固定 Java 主入口或类路径");
        }
    }

    private static IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException("MAVEN_JVM_CONFIG_UNSUPPORTED: " + detail);
    }
}
