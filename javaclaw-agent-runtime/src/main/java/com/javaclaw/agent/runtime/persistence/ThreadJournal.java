package com.javaclaw.agent.runtime.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.agent.conversation.ConversationWindow;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;

/** Atomic Thread/Turn/Item projection and durable event journal. */
public interface ThreadJournal {
    /** 创建 Thread 投影及首个事件/Outbox；parentThreadId、forkedFromTurnId 可为空，目录由服务端提供。 */
    AgentThread createThread(
            String workspaceId, Path workingDirectory, String title, ThreadId parentThreadId, TurnId forkedFromTurnId);

    /** 支持幂等键的创建端口；默认委托无键版本，生产持久实现必须覆盖并实现请求去重。 */
    default AgentThread createThread(
            String workspaceId,
            Path workingDirectory,
            String title,
            ThreadId parentThreadId,
            TurnId forkedFromTurnId,
            String idempotencyKey) {
        return createThread(workspaceId, workingDirectory, title, parentThreadId, forkedFromTurnId);
    }

    /** 在指定 Turn 处分支；workingDirectory 为 null 时沿用源目录，不修改源历史。 */
    AgentThread forkThread(ThreadId source, TurnId throughTurn, String title, Path workingDirectory);

    /** 在指定 Turn 处分支并沿用源目录，保留父/分支关系和基础 sequence。 */
    default AgentThread forkThread(ThreadId source, TurnId throughTurn, String title) {
        return forkThread(source, throughTurn, title, null);
    }

    /** 带幂等键的分支端口；默认委托无键版本，生产实现负责去重。 */
    default AgentThread forkThread(
            ThreadId source, TurnId throughTurn, String title, Path workingDirectory, String idempotencyKey) {
        return forkThread(source, throughTurn, title, workingDirectory);
    }

    /** 从 targetTurn 之前建立分支；生产持久实现应在单一事务复制此前的终态 Turn。默认实现显式拒绝，避免错误地把目标 Turn 也复制进去。 */
    default AgentThread forkBeforeTurn(
            ThreadId source, TurnId targetTurn, String title, Path workingDirectory, String idempotencyKey) {
        throw new UnsupportedOperationException("fork-before-turn is unavailable");
    }

    /** 重试启动失败后回滚新分支和内部幂等结果；默认实现只删除分支，事务型实现应保证同一请求 key 可再次使用。 */
    default void rollbackRetryBranch(ThreadId id, String branchIdempotencyKey, String turnIdempotencyKey) {
        deleteThread(id);
    }

    /** 按标识读取 Thread 投影，不加载整个 transcript；不存在返回 Optional.empty。 */
    Optional<AgentThread> findThread(ThreadId id);

    /** 列出持久 Thread；includeArchived 决定是否包含归档记录。 */
    List<AgentThread> listThreads(boolean includeArchived);

    /** 更新标题并原子追加事件和 Outbox；无显式版本保护。 */
    AgentThread updateThreadTitle(ThreadId id, String title);

    /** 带修订号/幂等键的标题更新端口；默认委托基础方法，生产实现负责乐观锁和去重。 */
    default AgentThread updateThreadTitle(ThreadId id, String title, long expectedRevision, String idempotencyKey) {
        return updateThreadTitle(id, title);
    }

    /** 保存 Thread 状态及相应事件/Outbox；调用方先协调活动 Turn。 */
    AgentThread setThreadStatus(ThreadId id, ThreadStatus status);

    /** 带修订号/幂等键的状态更新端口；默认委托基础方法，生产实现负责乐观锁和去重。 */
    default AgentThread setThreadStatus(
            ThreadId id, ThreadStatus status, long expectedRevision, String idempotencyKey) {
        return setThreadStatus(id, status);
    }

    /** 删除 Thread 及其持久关联记录；调用方负责先中断活动执行，不删除工作目录。 */
    void deleteThread(ThreadId id);

    /** 带修订号/幂等键的删除端口；默认委托基础方法，生产实现负责乐观锁和去重。 */
    default void deleteThread(ThreadId id, long expectedRevision, String idempotencyKey) {
        deleteThread(id);
    }

    /** 原子建立 Turn、内部执行尝试及初始输入 Item/事件；唯一活动 Turn 与幂等约束由存储兜底。 */
    AgentTurn startTurn(TurnStartCommand command);

    /** 读取单 Turn 持久投影；不存在时返回 Optional.empty。 */
    Optional<AgentTurn> findTurn(TurnId id);

    /** 读取单 Item 持久投影；不存在时返回 Optional.empty。 */
    Optional<StoredItem> findItem(ItemId id);

    /** 在指定 Thread 内查找幂等键对应的 Turn；不存在时返回空值，不创建新执行。 */
    Optional<AgentTurn> findTurnByIdempotencyKey(ThreadId threadId, String idempotencyKey);

    /** 仅当状态仍为 expected 时原子转为 next，并写入事件/Outbox；error 可为空，终态需保存完成时间。 */
    AgentTurn transitionTurn(TurnId id, TurnStatus expected, TurnStatus next, String error);

    /** 原子累加本次模型调用的用量增量并追加 usage 事件；不得重复传入累计总量。 */
    void recordUsage(ThreadId threadId, TurnId turnId, ModelUsage delta);

    /** 在模型请求前保存不可变的脱敏提示词快照，ordinal 在该 Turn 内唯一；不写入 token delta 事件。 */
    void recordPromptSnapshot(
            ThreadId threadId, TurnId turnId, com.javaclaw.agent.prompt.PromptSnapshot snapshot, int ordinal);

    /** 分配 Item 标识与排序位置，保存无最终内容的 STARTED 投影及生命周期事件。 */
    StoredItem startItem(ThreadId threadId, TurnId turnId, String kind);

    /** 将尚未终态的 Item 更新为 COMPLETED，并在同事务保存最终内容、事件和 Outbox。 */
    StoredItem completeItem(ItemId itemId, ThreadItem item);

    /** 将尚未终态的 Item 更新为 FAILED，最终内容为脱敏 ErrorItem；与事件/Outbox 原子提交。 */
    StoredItem failItem(ItemId itemId, String code, String message, boolean retryable);

    /** 一次性追加已知内容及指定状态的 Item，并原子写入对应事件和 Outbox。 */
    StoredItem appendItem(ThreadId threadId, TurnId turnId, ThreadItem item, ItemState state);

    /** 读取当前活动模型窗口；不存在时调用方从原始 transcript 组装。 */
    default Optional<ConversationWindow> activeConversationWindow(ThreadId threadId) {
        return Optional.empty();
    }

    /** 保存普通 Responses 调用产生的 canonical 状态；生产实现更新当前原生窗口并记录覆盖序号。 摘要窗口或 Provider/模型切换时不得覆写旧窗口正文。 */
    default void saveProviderConversationState(
            ThreadId threadId, String model, long coveredSequence, ProviderConversationState state, ModelUsage usage) {}

    /** 将 contextCompaction Item 完成与新活动窗口安装原子提交；失败不得改变旧窗口。 */
    default StoredItem completeCompaction(
            ItemId itemId, ThreadItem.ContextCompaction item, ConversationWindow.Replacement replacement) {
        return completeItem(itemId, item);
    }

    /** 读取指定 Thread 的持久 Item 序列；不包含尚未持久化的 token delta。 */
    List<StoredItem> items(ThreadId threadId);

    /** 按严格递增序号读取 afterSequence 之后最多 limit 条持久事件；afterSequence 为排他游标。 */
    List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit);

    /** 读取 Thread、Turn 和 Item 的一致性持久快照；Thread 必须存在。 */
    ThreadSnapshot snapshot(ThreadId threadId);

    /** 将异常退出遗留的非终态 Turn 收敛为 INTERRUPTED、未完成 Item 收敛为 FAILED；返回恢复 Turn 数量。 */
    int recoverInterruptedTurns();
}
