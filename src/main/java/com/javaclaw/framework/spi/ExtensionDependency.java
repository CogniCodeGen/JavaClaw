package com.javaclaw.framework.spi;

import java.util.Objects;

public record ExtensionDependency(String extensionId, String versionRange, boolean optional) {
    public ExtensionDependency {
        extensionId = Objects.requireNonNull(extensionId, "extensionId").trim();
        versionRange = Objects.requireNonNull(versionRange, "versionRange").trim();
        if (extensionId.isEmpty() || versionRange.isEmpty()) {
            throw new IllegalArgumentException("extension dependency values must not be blank");
        }
    }
}
