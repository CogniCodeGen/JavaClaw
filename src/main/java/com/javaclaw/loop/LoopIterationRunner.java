package com.javaclaw.loop;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.loop.model.IterationResult;

/**
 * 单轮执行端口：把「一轮该怎么跑」与确定性引擎解耦。
 *
 * <p>Phase 1 的 {@link LoopController} 只依赖本端口，因此整套「完成/继续/停止」决策
 * 可用脚本化的假实现离线单测。生产实现（Phase 2）会复用 {@code ScheduledTaskAgent}
 * 的隔离范式：独立子智能体 + 独立 toolkit + 每轮独立记忆 + 独立订阅，阻塞跑完一轮
 * 并把执行体的最终回复截获成 {@link IterationResult} 返回。</p>
 */
@FunctionalInterface
public interface LoopIterationRunner {

    /**
     * 跑一轮，<b>阻塞</b>直到本轮流式完成 / 出错 / 超时。
     *
     * @param prompt    本轮的用户提示（目标 + 接力上下文，由 {@code CarryContext} 组装）
     * @param callbacks 事件回调（把本轮思考/回复/工具结果实时透传给 UI）
     * @return 本轮产出；异常/超时应折成 {@link IterationResult#failed()} 而非抛出
     */
    IterationResult runOnce(String prompt, ConversationCallbacks callbacks);
}
