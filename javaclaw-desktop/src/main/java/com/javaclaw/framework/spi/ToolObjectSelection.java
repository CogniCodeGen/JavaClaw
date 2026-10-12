package com.javaclaw.framework.spi;

import java.util.Objects;
import java.util.Set;

/** 宿主仅暴露原工具对象的指定方法，同时保留真实实现身份及回执来源。 */
public record ToolObjectSelection(Object source, Set<String> toolNames) {
    public ToolObjectSelection {
        source = Objects.requireNonNull(source, "source");
        toolNames = Set.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    }
}
