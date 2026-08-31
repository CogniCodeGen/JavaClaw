package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.WorkspaceId;

/** Thread lifecycle commands and durable transcript queries. */
public interface ThreadUseCases {
    /** 在已登记 Workspace 根目录创建 Thread；此便捷入口不携带幂等键。 */
    default AgentThread startThread(WorkspaceId workspaceId, String title) {
        return startThread(workspaceId, title, null);
    }

    /**
     * 在未锁定 Workspace 创建 Thread 并发布持久事件；idempotencyKey 可为空。
     *
     * @throws IllegalStateException Workspace 被安全锁定或运行时已关闭
     */
    AgentThread startThread(WorkspaceId workspaceId, String title, String idempotencyKey);

    /** 在父 Workspace 下创建独立子 Thread；workingDirectory 必须为服务端管理的现存目录，不能透传任意客户端 cwd。 */
    AgentThread startChildThread(ThreadId parentThreadId, String title, Path workingDirectory);

    /** 更新展示标题；此进程内便捷入口不提供显式版本或幂等保护。 */
    default AgentThread updateThread(ThreadId id, String title) {
        return updateThread(id, title, -1, null);
    }

    /** 按 expectedRevision 更新标题并发布事件；幂等键用于去重，版本冲突必须拒绝覆盖。 */
    AgentThread updateThread(ThreadId id, String title, long expectedRevision, String idempotencyKey);

    /** 在 throughTurn 处复制可恢复上下文，沿用原工作目录且不携带幂等键。 */
    default AgentThread forkThread(ThreadId source, TurnId throughTurn, String title) {
        return forkThread(source, throughTurn, title, null, null);
    }

    /** 在指定 Turn 分支并使用服务端决定的目录；null 目录沿用原 Thread，未启用请求幂等。 */
    default AgentThread forkThread(ThreadId source, TurnId throughTurn, String title, Path workingDirectory) {
        return forkThread(source, throughTurn, title, workingDirectory, null);
    }

    /** 创建关联父 Thread、分支 Turn 和基础 sequence 的分支；原 Thread 历史保持不变。 */
    AgentThread forkThread(
            ThreadId source, TurnId throughTurn, String title, Path workingDirectory, String idempotencyKey);

    /** 从目标 Turn 之前创建分支；源历史不变，目标必须属于源 Thread 且为终态。 */
    default AgentThread forkBeforeTurn(
            ThreadId source, TurnId targetTurn, String title, Path workingDirectory, String idempotencyKey) {
        throw new UnsupportedOperationException("fork-before-turn is unavailable");
    }

    /** 重试启动失败后回滚新分支和内部幂等结果；调用方只可传入本次尚未对用户发布成功的分支。 */
    default void rollbackRetryBranch(ThreadId id, String branchIdempotencyKey, String turnIdempotencyKey) {
        deleteThread(id);
    }

    /** 读取完整持久快照；Thread 不存在时返回 Optional.empty，不包含瞬时 delta。 */
    Optional<ThreadSnapshot> readThread(ThreadId id);

    /** 按标识读取单 Turn，避免为一次状态查询加载完整 transcript；不存在时返回空值。 */
    Optional<AgentTurn> readTurn(TurnId id);

    /** 查询同 Thread 的幂等启动结果；供辅助任务在 Profile 已更新后仍安全重放，不再次调用模型。 */
    Optional<AgentTurn> readTurnByIdempotencyKey(ThreadId threadId, String idempotencyKey);

    /** 按标识读取单 Item 持久投影；不存在时返回 Optional.empty。 */
    Optional<StoredItem> readItem(ItemId id);

    /** 列出持久 Thread；includeArchived 决定是否包含归档记录。 */
    List<AgentThread> listThreads(boolean includeArchived);

    /** 归档无活动 Turn 的 Thread；便捷入口不携带版本或幂等键，不删除历史。 */
    default AgentThread archiveThread(ThreadId id) {
        return archiveThread(id, -1, null);
    }

    /** 按版本归档无活动 Turn 的 Thread 并发布事件；归档后不接受新 Turn。 */
    AgentThread archiveThread(ThreadId id, long expectedRevision, String idempotencyKey);

    /** 将 Thread 恢复为活跃状态；便捷入口不携带版本或幂等键。 */
    default AgentThread unarchiveThread(ThreadId id) {
        return unarchiveThread(id, -1, null);
    }

    /** 按版本恢复 Thread 活跃状态并发布事件；重复幂等请求不应产生重复变更。 */
    AgentThread unarchiveThread(ThreadId id, long expectedRevision, String idempotencyKey);

    /** 中断活动执行后删除 Thread 持久记录；此入口不携带版本或幂等保护，不删除工作区文件。 */
    default void deleteThread(ThreadId id) {
        deleteThread(id, -1, null);
    }

    /** 中断活动执行并按版本删除 Thread 持久记录；不删除用户工作区文件。 */
    void deleteThread(ThreadId id, long expectedRevision, String idempotencyKey);

    /** 按严格递增序号读取 afterSequence 之后最多 limit 条持久事件；afterSequence 为排他游标。 */
    List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit);
}
