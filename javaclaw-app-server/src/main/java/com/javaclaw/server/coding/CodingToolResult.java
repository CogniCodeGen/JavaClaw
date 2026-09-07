package com.javaclaw.server.coding;

import java.util.List;

import com.javaclaw.api.ToolExecutionFact;

/** 平台内部执行结果，事实和成功状态只能由执行边界创建。 */
record CodingToolResult(Object value, List<ToolExecutionFact> facts, boolean success) {
    CodingToolResult {
        facts = List.copyOf(facts);
    }

    static CodingToolResult value(Object value) {
        return new CodingToolResult(value, List.of(), true);
    }
}
