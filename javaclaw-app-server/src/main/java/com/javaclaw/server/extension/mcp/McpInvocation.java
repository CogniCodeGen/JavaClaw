package com.javaclaw.server.extension.mcp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.ToolExecutionContext;

/**
 * Per-tool-call MRTR state; counters are never shared across Turns.
 *
 * @param context 非空工具调用快照，包含当前 Turn 配置
 * @param events 非空 Item 输出通道
 * @param nestedModelCalls 本次 MCP 调用的独立计数器；null 创建新计数器，不跨 Turn 共享
 * @param maximumNestedModelCalls 嵌套模型调用上限，范围 0 到 16
 */
public record McpInvocation(
        ToolExecutionContext context, ItemSink events, AtomicInteger nestedModelCalls, int maximumNestedModelCalls) {
    /** 校验预算并固定调用归属；本地计数器与显式 TurnScope 总预算共同约束 Sampling。 */
    public McpInvocation {
        context = Objects.requireNonNull(context, "context");
        events = Objects.requireNonNull(events, "events");
        nestedModelCalls = nestedModelCalls == null ? new AtomicInteger() : nestedModelCalls;
        if (maximumNestedModelCalls < 0 || maximumNestedModelCalls > 16) {
            throw new IllegalArgumentException("MCP nested model-call budget is invalid");
        }
    }

    /** 创建默认最多 4 次嵌套模型调用的 MRTR 上下文。 */
    public McpInvocation(ToolExecutionContext context, ItemSink events) {
        this(context, events, new AtomicInteger(), 4);
    }

    /** 在发起 Sampling 前消耗一次本地预算；超出上限抛出异常，不执行新的模型调用。 */
    public void consumeNestedModelCall() {
        if (nestedModelCalls.incrementAndGet() > maximumNestedModelCalls) {
            throw new IllegalStateException("MCP nested model-call budget exceeded");
        }
    }
}
