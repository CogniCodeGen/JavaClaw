package com.javaclaw.server.toolchain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

/** 读取 asdf 固定版本；多候选、system、路径或 ref 不能绕过托管发行目录。 */
final class ToolVersionsDeclarations {
    private static final Map<String, ToolchainKind> KINDS = Map.of(
            "java",
            ToolchainKind.JDK,
            "nodejs",
            ToolchainKind.NODE,
            "python",
            ToolchainKind.PYTHON,
            "maven",
            ToolchainKind.MAVEN,
            "gradle",
            ToolchainKind.GRADLE,
            "npm",
            ToolchainKind.NPM,
            "pnpm",
            ToolchainKind.PNPM);

    private ToolVersionsDeclarations() {}

    static Result parse(String source) {
        Map<ToolchainKind, String> versions = new EnumMap<>(ToolchainKind.class);
        Map<ToolchainKind, List<String>> errors = new EnumMap<>(ToolchainKind.class);
        for (String line : source.lines().toList()) {
            String text = line.split("#", 2)[0].strip();
            if (text.isEmpty()) {
                continue;
            }
            String[] fields = text.split("\\s+");
            ToolchainKind kind = KINDS.get(fields[0]);
            if (kind == null) {
                continue;
            }
            if (fields.length != 2
                    || !fields[1].matches("[0-9]+(?:\\.[0-9]+){0,2}")
                    || versions.putIfAbsent(kind, fields[1]) != null) {
                errors.put(kind, List.of(".tool-versions: 仅支持每种工具一项静态数字版本，不接受重复或宿主回退"));
            }
        }
        return new Result(Map.copyOf(versions), Map.copyOf(errors));
    }

    record Result(Map<ToolchainKind, String> versions, Map<ToolchainKind, List<String>> errors) {}
}
