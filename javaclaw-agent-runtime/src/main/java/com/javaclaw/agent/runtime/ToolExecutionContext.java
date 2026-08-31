package com.javaclaw.agent.runtime;

import java.util.Objects;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.TurnConfig;

/**
 * 工具执行所需的 Turn 快照；权限来自已解析 config，而不是模型参数。
 *
 * @param thread 所属 Thread 的非空状态快照
 * @param turn 所属 Turn 的非空状态快照
 * @param call 非空模型工具调用提案
 * @param scope 本 Turn 共享的非空预算与取消作用域
 * @param config 本次执行使用的非空 Turn 配置快照
 */
public record ToolExecutionContext(
        AgentThread thread, AgentTurn turn, ModelToolCall call, TurnConfig config, TurnScope scope) {
    /** 创建独立工具诊断上下文；真实 Turn 必须传入其已有 scope，不能重置预算。 */
    public ToolExecutionContext(AgentThread thread, AgentTurn turn, ModelToolCall call, TurnConfig config) {
        this(thread, turn, call, config, TurnScope.from(config, new java.util.concurrent.atomic.AtomicBoolean()));
    }

    /** 要求 Thread、Turn、调用提案和配置完整；构造本身不授权执行。 */
    public ToolExecutionContext {
        scope = Objects.requireNonNull(scope, "scope");
        thread = Objects.requireNonNull(thread, "thread");
        turn = Objects.requireNonNull(turn, "turn");
        call = Objects.requireNonNull(call, "call");
        config = Objects.requireNonNull(config, "config");
    }
}
