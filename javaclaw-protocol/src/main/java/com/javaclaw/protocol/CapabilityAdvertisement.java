package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Set;

/**
 * initialize 中的能力声明。
 *
 * @param stableCapabilities 客户端理解的 5.x stable 能力
 * @param requestedExperimentalCapabilities 客户端主动请求的实验能力
 */
public record CapabilityAdvertisement(Set<String> stableCapabilities, Set<String> requestedExperimentalCapabilities) {
    /** 复制并校验能力名。 */
    public CapabilityAdvertisement {
        stableCapabilities = copyNames(stableCapabilities, "stableCapabilities");
        requestedExperimentalCapabilities =
                copyNames(requestedExperimentalCapabilities, "requestedExperimentalCapabilities");
    }

    private static Set<String> copyNames(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return Set.copyOf(values);
    }
}
