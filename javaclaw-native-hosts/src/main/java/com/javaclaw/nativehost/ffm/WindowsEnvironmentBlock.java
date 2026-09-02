package com.javaclaw.nativehost.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.Map;

/** 构造 CreateProcessAsUserW 所需的双 NUL 结尾 Unicode 环境块。 */
final class WindowsEnvironmentBlock {
    private WindowsEnvironmentBlock() {}

    static MemorySegment encode(Arena arena, Map<String, String> environment) {
        StringBuilder block = new StringBuilder();
        environment.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
                .forEach(entry -> block.append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append('\0'));
        return WindowsSandboxNative.wide(arena, block.toString());
    }
}
