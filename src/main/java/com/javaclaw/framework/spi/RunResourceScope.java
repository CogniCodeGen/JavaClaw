package com.javaclaw.framework.spi;

import java.util.function.Supplier;

/** Shared, process-local resources whose lifetime is exactly one framework Run. */
public interface RunResourceScope {
    <T> T getOrCreate(String key, Class<T> type, Supplier<? extends T> factory);
}
