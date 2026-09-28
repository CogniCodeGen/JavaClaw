package com.javaclaw.workflow.model;

import java.util.Objects;

/** 条件边按 priority 从小到大匹配；defaultEdge 必须是同源条件边中的最后兜底。 */
public record EdgeDefinition(
        String id,
        String source,
        String target,
        EdgeKind kind,
        ConditionRule condition,
        int priority,
        boolean defaultEdge) {

    public EdgeDefinition {
        id = id == null ? "" : id.trim();
        source = source == null ? "" : source.trim();
        target = target == null ? "" : target.trim();
        kind = Objects.requireNonNull(kind, "kind");
    }
}
