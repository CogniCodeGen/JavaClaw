package com.javaclaw.framework.extension;

import java.util.Objects;

/** Associates an SPI contribution with the exact extension that owns it. */
public record OwnedContribution<T>(String extensionId, T value) {
    public OwnedContribution {
        extensionId = Objects.requireNonNull(extensionId, "extensionId");
        value = Objects.requireNonNull(value, "value");
    }
}
