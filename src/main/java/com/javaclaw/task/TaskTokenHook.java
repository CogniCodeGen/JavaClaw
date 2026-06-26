package com.javaclaw.task;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.function.BiConsumer;

/**
 * 托管任务 token 用量追踪钩子
 *
 * <p>监听 {@link PostReasoningEvent}：每轮 ReAct 推理结束后，从返回消息中提取
 * {@link ChatUsage} 并上报真实 token 用量。适用于规划、监督、子任务执行和验收等
 * 所有内部智能体调用，通过对每个智能体挂载同一钩子实现按任务的跨智能体汇总。</p>
 *
 * <p>优先级 900（低优先级），在业务钩子（监督钩子、循环检测）之后运行，
 * 即使前序钩子抛出异常也尽量不影响计数收敛。</p>
 *
 * @author JavaClaw
 */
public class TaskTokenHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(TaskTokenHook.class);

    /** token 回调：参数为 (inputTokens, outputTokens)，由调用方决定如何累计 */
    private final BiConsumer<Long, Long> onTokensConsumed;

    public TaskTokenHook(BiConsumer<Long, Long> onTokensConsumed) {
        this.onTokensConsumed = onTokensConsumed;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            try {
                Msg msg = post.getReasoningMessage();
                if (msg != null) {
                    ChatUsage usage = msg.getChatUsage();
                    if (usage != null
                            && (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0)) {
                        onTokensConsumed.accept((long) usage.getInputTokens(),
                                (long) usage.getOutputTokens());
                    }
                }
            } catch (Throwable t) {
                // 防御：钩子异常不应中断智能体执行
                log.debug("读取推理消息 ChatUsage 失败，忽略本轮", t);
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 900;
    }
}
