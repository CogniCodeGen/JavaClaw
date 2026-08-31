package com.javaclaw.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadResumeInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;
import com.javaclaw.sdk.model.TurnStartRequest;

/** Thread/Turn/Item 生命周期客户端；远程方法返回 Future，版本冲突等 RPC 错误以异常完成，执行完成通过事件观察。 */
public final class ThreadClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    ThreadClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 在已登记 Workspace 创建 Thread；idempotencyKey 用于重复请求去重，目录由服务端决定。 */
    public CompletableFuture<ThreadInfo> start(String workspaceId, String title, String idempotencyKey) {
        return protocol.startThread(workspaceId, title, idempotencyKey).thenApply(mapper::thread);
    }

    /** 从排他 afterSequence 游标恢复订阅，返回持久快照、后续事件和活动 Item 内存快照。 */
    public CompletableFuture<ThreadResumeInfo> resume(String threadId, long afterSequence) {
        return protocol.resumeThread(threadId, afterSequence)
                .thenApply(value -> new ThreadResumeInfo(
                        mapper.snapshot(value.snapshot()),
                        value.events().stream().map(mapper::event).toList(),
                        value.liveItems().stream().map(mapper::document).toList()));
    }

    /** 读取 Thread 完整持久 transcript，不把内存 token delta 当作最终内容。 */
    public CompletableFuture<ThreadSnapshot> read(String threadId) {
        return protocol.readThread(threadId).thenApply(mapper::snapshot);
    }

    /** 有界等待指定 Turn 终态并返回持久快照；超时或取消仅停止等待，不代替用户的 interrupt 授权。 */
    public CompletableFuture<ThreadSnapshot> awaitTurn(String threadId, String turnId, java.time.Duration timeout) {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(java.time.Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 24 hours");
        }
        var result = new CompletableFuture<ThreadSnapshot>();
        long deadline = System.nanoTime() + timeout.toNanos();
        Thread worker = Thread.ofVirtual().name("javaclaw-sdk-await-turn").start(() -> {
            try {
                long delay = 100;
                while (!result.isDone()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new java.util.concurrent.TimeoutException("Turn wait timed out");
                    }
                    ThreadSnapshot snapshot = read(threadId).get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                    var turn = snapshot.turns().stream()
                            .filter(value -> turnId.equals(value.id()))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalStateException("Turn is not present in the requested Thread"));
                    if (turn.completedAt() != null) {
                        result.complete(snapshot);
                        return;
                    }
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.max(
                            0,
                            Math.min(
                                    deadline - System.nanoTime(),
                                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay))));
                    delay = Math.min(1_000, delay * 2);
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                worker.interrupt();
            }
        });
        return result;
    }

    /** 列出 Thread；includeArchived 决定是否包含归档记录。 */
    public CompletableFuture<List<ThreadInfo>> list(boolean includeArchived) {
        return protocol.listThreads(includeArchived)
                .thenApply(values -> values.stream().map(mapper::thread).toList());
    }

    /** 列出工作区最近 256 个工作树及清理后的备份记录；running 仅为展示快照。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.WorktreeInfo>> worktrees(String workspaceId) {
        return protocol.worktrees(workspaceId)
                .thenApply(values -> values.stream().map(mapper::worktree).toList());
    }

    /** 为已退出的子 Thread 导出相对合成基线的补丁附件；不修改父工作区，版本冲突拒绝过期操作。 */
    public CompletableFuture<com.javaclaw.sdk.model.WorktreePatchInfo> exportWorktreePatch(
            String childThreadId, long expectedRevision) {
        return protocol.worktreePatch(childThreadId, expectedRevision).thenApply(mapper::worktreePatch);
    }

    /** 用户确认后清理受控工作树；discardUnmerged 必须显式允许丢弃未合并内容。 服务端先保存补丁备份及幂等意图；活动目录拒绝清理，重复 key 返回同一结果。 */
    public CompletableFuture<com.javaclaw.sdk.model.WorktreeInfo> cleanupWorktree(
            String childThreadId, long expectedRevision, boolean discardUnmerged, String idempotencyKey) {
        return protocol.cleanupWorktree(childThreadId, expectedRevision, discardUnmerged, idempotencyKey)
                .thenApply(mapper::worktree);
    }

    /** 在 throughTurnId 处创建分支并保留父/基础序号关系；不修改源 Thread。 */
    public CompletableFuture<ThreadInfo> fork(
            String threadId, String throughTurnId, String title, String idempotencyKey) {
        return protocol.forkThread(threadId, throughTurnId, title, idempotencyKey)
                .thenApply(mapper::thread);
    }

    /** 读取每个 Turn 的 Profile、Provider、模型、状态、时间和可用 Token；不返回原始配置或隐藏推理。 */
    public CompletableFuture<com.javaclaw.sdk.model.ThreadExecutionSummaryInfo> executionSummary(String threadId) {
        return protocol.threadExecutionSummary(threadId).thenApply(mapper::executionSummary);
    }

    /**
     * 从目标 Turn 之前建立新分支并启动重试。replacementText 为 null 时复用原输入；非 null 时替换文本并重新校验原附件引用。源 Thread 保持不变，Profile、审批和沙箱均由服务端重新解析。
     */
    public CompletableFuture<com.javaclaw.sdk.model.ThreadBranchStartInfo> retryInNewBranch(
            String threadId, String targetTurnId, String replacementText, String profileId, String idempotencyKey) {
        return protocol.retryInNewBranch(threadId, targetTurnId, replacementText, profileId, idempotencyKey)
                .thenApply(mapper::branchStart);
    }

    /** 按 expectedRevision 更新 Thread 标题；重复幂等请求不重复产生变更。 */
    public CompletableFuture<ThreadInfo> update(
            String threadId, String title, long expectedRevision, String idempotencyKey) {
        return protocol.updateThread(threadId, title, expectedRevision, idempotencyKey)
                .thenApply(mapper::thread);
    }

    /** 按版本归档无活动 Turn 的 Thread，不删除历史内容。 */
    public CompletableFuture<ThreadInfo> archive(String threadId, long expectedRevision, String idempotencyKey) {
        return protocol.archiveThread(threadId, expectedRevision, idempotencyKey)
                .thenApply(mapper::thread);
    }

    /** 按版本恢复 Thread 活跃状态，允许再次启动 Turn。 */
    public CompletableFuture<ThreadInfo> unarchive(String threadId, long expectedRevision, String idempotencyKey) {
        return protocol.unarchiveThread(threadId, expectedRevision, idempotencyKey)
                .thenApply(mapper::thread);
    }

    /** 按版本请求删除 Thread 持久数据并中断活动执行；不删除工作区项目文件。 */
    public CompletableFuture<Boolean> delete(String threadId, long expectedRevision, String idempotencyKey) {
        return protocol.deleteThread(threadId, expectedRevision, idempotencyKey);
    }

    /** 使用 Thread/Profile、文本/附件和收窄选项启动 Turn；返回启动快照，不能通过此请求覆盖模型或沙箱权限。 */
    public CompletableFuture<TurnInfo> startTurn(TurnStartRequest request) {
        return protocol.startTurn(
                        request.threadId(),
                        mapper.turnInputs(request.input()),
                        request.profileId(),
                        mapper.turnRestrictions(request),
                        request.idempotencyKey())
                .thenApply(mapper::turn);
    }

    /** 异步启动无工具、有限预算的压缩 Turn；服务端沿用最近真实 Turn 的 Provider 与模型。 */
    public CompletableFuture<Void> startCompaction(String threadId) {
        return protocol.startCompaction(threadId);
    }

    /** 显式采用已完成 Plan Item 并创建执行 Turn；profileRevision 与决策绑定当前用户决定，key 防止重复启动。 */
    public CompletableFuture<TurnInfo> adoptPlan(
            String threadId, String planItemId, String profileId, long profileRevision, String decisions, String key) {
        return protocol.adoptPlan(threadId, planItemId, profileId, profileRevision, decisions, key)
                .thenApply(mapper::turn);
    }

    /** 给活动 Turn 追加文本；返回是否接受，不创建第二个活动 Turn。 */
    public CompletableFuture<Boolean> steer(String turnId, String text) {
        return protocol.steerText(turnId, text);
    }

    /** 取消目标 Turn 并由服务端传播到子 Thread；没有可取消执行时返回 false。 */
    public CompletableFuture<Boolean> interrupt(String turnId) {
        return protocol.interrupt(turnId);
    }

    /** 提交指定审批决议；批准只允许进入后续沙箱步骤，不代表无约束执行。 */
    public CompletableFuture<Boolean> respondToApproval(String approvalId, boolean approved) {
        return protocol.respondToApproval(approvalId, approved);
    }

    /** 提交回答或显式取消；返回请求是否仍可接受该回答，cancelled 与空回答语义不同。 */
    public CompletableFuture<Boolean> respondToUserInput(String requestId, String value, boolean cancelled) {
        return protocol.respondToUserInput(requestId, value, cancelled);
    }

    /** 读取排他 afterSequence 之后最多 limit 条持久事件，按 sequence 升序恢复投影。 */
    public CompletableFuture<List<EventInfo>> events(String threadId, long afterSequence, int limit) {
        return protocol.events(threadId, afterSequence, limit)
                .thenApply(values -> values.stream().map(mapper::event).toList());
    }
}
