package com.javaclaw.nativehost.sandbox;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.javaclaw.api.ResourceLimits;

/** 资源 helper 的固定参数；分隔符后的 argv 原样保留，不经过 shell。 */
record SandboxHelperArguments(Duration timeout, ResourceLimits limits, List<String> target) {
    SandboxHelperArguments {
        java.util.Objects.requireNonNull(timeout, "timeout");
        java.util.Objects.requireNonNull(limits, "limits");
        target = List.copyOf(target);
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target argv must not be empty");
        }
    }

    static SandboxHelperArguments parse(String[] arguments) {
        if (arguments.length < 7 || !"--".equals(arguments[5])) {
            throw new IllegalArgumentException(
                    "usage: <timeout-ms> <memory-bytes> <output-bytes> <child-processes> <open-files> -- <argv...>");
        }
        Duration timeout = Duration.ofMillis(positiveLong(arguments[0], "timeout-ms"));
        ResourceLimits limits = new ResourceLimits(
                positiveLong(arguments[1], "memory-bytes"),
                positiveLong(arguments[2], "output-bytes"),
                positiveInt(arguments[3], "child-processes"),
                positiveInt(arguments[4], "open-files"));
        List<String> target = List.copyOf(Arrays.asList(arguments).subList(6, arguments.length));
        return new SandboxHelperArguments(timeout, limits, target);
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
}
