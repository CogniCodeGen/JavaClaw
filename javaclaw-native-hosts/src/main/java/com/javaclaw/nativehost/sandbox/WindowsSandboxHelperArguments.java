package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.ffm.WindowsSandboxContext;
import com.javaclaw.nativehost.ffm.WindowsSandboxRequest;

/** 解析 Windows helper 的固定位置参数；目标 argv 位于唯一分隔符之后。 */
final class WindowsSandboxHelperArguments {
    private static final int FIXED_ARGUMENTS = 9;

    private WindowsSandboxHelperArguments() {}

    static WindowsSandboxRequest parse(String[] arguments) {
        if (arguments.length <= FIXED_ARGUMENTS) {
            throw new IllegalArgumentException("Windows Sandbox helper arguments are incomplete");
        }
        int readCount = nonNegativeInt(arguments[7], "read-count");
        int writeCount = nonNegativeInt(arguments[8], "write-count");
        int separator = Math.addExact(FIXED_ARGUMENTS, Math.addExact(readCount, writeCount));
        if (separator >= arguments.length || !"--".equals(arguments[separator]) || separator + 1 >= arguments.length) {
            throw new IllegalArgumentException("Windows Sandbox helper path counts or argv delimiter are invalid");
        }
        ResourceLimits limits = new ResourceLimits(
                positiveLong(arguments[3], "memory-bytes"),
                positiveLong(arguments[4], "output-bytes"),
                positiveInt(arguments[5], "child-processes"),
                positiveInt(arguments[6], "open-files"));
        List<Path> reads = paths(arguments, FIXED_ARGUMENTS, readCount);
        List<Path> writes = paths(arguments, FIXED_ARGUMENTS + readCount, writeCount);
        List<String> target = List.copyOf(Arrays.asList(arguments).subList(separator + 1, arguments.length));
        return new WindowsSandboxRequest(
                target,
                new WindowsSandboxContext(Path.of(arguments[0]), Map.copyOf(System.getenv())),
                reads,
                writes,
                parseBoolean(arguments[1]),
                Duration.ofMillis(positiveLong(arguments[2], "timeout-ms")),
                limits);
    }

    private static List<Path> paths(String[] arguments, int offset, int count) {
        return Arrays.stream(arguments, offset, offset + count).map(Path::of).toList();
    }

    private static boolean parseBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("allow-delete must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static long positiveLong(String value, String name) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static int positiveInt(String value, String name) {
        return Math.toIntExact(positiveLong(value, name));
    }

    private static int nonNegativeInt(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return parsed;
    }
}
