package com.javaclaw.framework.spi;

import java.util.List;
import java.util.Objects;

/** Run-scoped annotated tool objects plus the lifecycle that owns their mutable resources. */
public record ToolObjectBundle(List<Object> objects, AutoCloseable lifecycle) {
    public ToolObjectBundle {
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        lifecycle = lifecycle == null ? () -> {} : lifecycle;
    }

    public static ToolObjectBundle of(List<Object> objects) {
        return new ToolObjectBundle(objects, () -> {});
    }
}
