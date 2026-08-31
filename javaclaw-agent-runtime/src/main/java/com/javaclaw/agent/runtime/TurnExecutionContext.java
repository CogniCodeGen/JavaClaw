package com.javaclaw.agent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.TurnSteering;

/**
 * 一次 Turn 执行的固定输入与可取消句柄；仅 interrupted/steering 是运行中交互通道。
 *
 * @param thread 所属 Thread 的非空状态快照
 * @param turn 所属 Turn 的非空状态快照
 * @param priorItems 执行前已持久化的非空 Item 快照列表
 * @param interrupted 非空协作式取消标志，由运行时维护
 * @param scope 非空显式预算与取消作用域，工具和辅助模型调用必须共享
 * @param steering 追加输入通道；null 使用不产生输入的 NONE 实现
 * @param conversationWindow Turn 开始时的活动模型窗口；null 表示完整 transcript
 */
public record TurnExecutionContext(
        AgentThread thread,
        AgentTurn turn,
        List<StoredItem> priorItems,
        AtomicBoolean interrupted,
        TurnSteering steering,
        TurnScope scope,
        com.javaclaw.agent.conversation.ConversationWindow conversationWindow) {
    /** 由运行时根 Turn 创建有限作用域；子调用必须使用显式 scope 的完整构造器。 */
    public TurnExecutionContext(
            AgentThread thread,
            AgentTurn turn,
            List<StoredItem> priorItems,
            AtomicBoolean interrupted,
            TurnSteering steering) {
        this(thread, turn, priorItems, interrupted, steering, TurnScope.from(turn.config(), interrupted), null);
    }

    /** 兼容显式 TurnScope 的现有调用；未传入窗口时按完整 transcript 组装。 */
    public TurnExecutionContext(
            AgentThread thread,
            AgentTurn turn,
            List<StoredItem> priorItems,
            AtomicBoolean interrupted,
            TurnSteering steering,
            TurnScope scope) {
        this(thread, turn, priorItems, interrupted, steering, scope, null);
    }

    /** 固定历史 Item，保留取消标志和 steering 通道用于执行过程中的协作交互。 */
    public TurnExecutionContext {
        scope = Objects.requireNonNull(scope, "scope");
        thread = Objects.requireNonNull(thread, "thread");
        turn = Objects.requireNonNull(turn, "turn");
        priorItems = List.copyOf(Objects.requireNonNull(priorItems, "priorItems"));
        interrupted = Objects.requireNonNull(interrupted, "interrupted");
        steering = steering == null ? TurnSteering.NONE : steering;
    }

    /** 返回与当前 Provider/模型匹配的 opaque 状态；摘要或环境切换时为空。 */
    public ProviderConversationState conversationState() {
        if (conversationWindow == null
                || conversationWindow.strategy() != com.javaclaw.agent.conversation.ConversationWindow.Strategy.NATIVE
                || !conversationWindow.provider().equalsIgnoreCase(turn.config().provider())
                || !conversationWindow.model().equals(turn.config().model())) {
            return null;
        }
        return conversationWindow.providerState();
    }

    /**
     * 在模型或工具步骤边界检查取消标志和当前 Java 线程中断。
     *
     * @throws InterruptedException Turn 已取消或执行线程已中断；调用方应停止后续副作用
     */
    public void throwIfInterrupted() throws InterruptedException {
        scope.check();
    }
}
