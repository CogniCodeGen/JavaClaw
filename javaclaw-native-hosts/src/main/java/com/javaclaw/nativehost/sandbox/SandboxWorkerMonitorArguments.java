package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** 可信监护 helper 的固定参数；用户工具不能选择控制目录、平台隔离方式或租约期限。 */
record SandboxWorkerMonitorArguments(
        Path control,
        Duration idle,
        long memoryBytes,
        int childProcesses,
        boolean nativeTreeLimits,
        boolean bootstrap,
        List<String> target) {
    SandboxWorkerMonitorArguments {
        target = List.copyOf(target);
        if (idle.compareTo(Duration.ofSeconds(1)) < 0
                || idle.compareTo(Duration.ofMinutes(15)) > 0
                || memoryBytes < 1
                || childProcesses < 1
                || target.isEmpty()) {
            throw new IllegalArgumentException("Invalid Worker monitor bounds");
        }
    }

    static SandboxWorkerMonitorArguments parse(String[] arguments) {
        if (arguments.length < 8 || !"--".equals(arguments[6])) {
            throw new IllegalArgumentException("Invalid Worker monitor arguments");
        }
        return new SandboxWorkerMonitorArguments(
                Path.of(arguments[0]),
                Duration.ofMillis(Long.parseLong(arguments[1])),
                Long.parseLong(arguments[2]),
                Integer.parseInt(arguments[3]),
                flag(arguments[4]),
                flag(arguments[5]),
                Arrays.asList(arguments).subList(7, arguments.length));
    }

    private static boolean flag(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Invalid Worker monitor flag");
        }
        return Boolean.parseBoolean(value);
    }
}
