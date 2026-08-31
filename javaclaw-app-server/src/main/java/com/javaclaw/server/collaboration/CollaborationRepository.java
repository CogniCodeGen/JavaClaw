package com.javaclaw.server.collaboration;

import java.time.Instant;
import java.util.Optional;

import com.javaclaw.core.api.ThreadId;

/** Durable idempotency and parent-child metadata for subagent spawning. */
public interface CollaborationRepository {
    /** 按父 Thread 和幂等键查询已有子任务；不存在返回 Optional.empty。 */
    Optional<SpawnRecord> findSpawn(ThreadId parentThreadId, String idempotencyKey);

    /** 保存父子 Thread、任务参数与去重键；返回持久记录，重放时用于核对参数而非重复创建。 */
    SpawnRecord recordSpawn(
            ThreadId parentThreadId,
            ThreadId childThreadId,
            boolean writable,
            String profileId,
            String task,
            String idempotencyKey);

    /**
     * 子任务创建的持久幂等记录，不允许子 Thread 直接修改父状态。
     *
     * @param parentThreadId 父 Thread 标识
     * @param childThreadId 独立子 Thread 标识
     * @param writable 该子任务是否请求并分配了写型执行模式
     * @param profileId 执行 Profile 标识
     * @param task 子任务描述，非空白
     * @param idempotencyKey 请求去重键；记录重放时必须核对原请求参数
     * @param createdAt 创建时间
     */
    record SpawnRecord(
            ThreadId parentThreadId,
            ThreadId childThreadId,
            boolean writable,
            String profileId,
            String task,
            String idempotencyKey,
            Instant createdAt) {}
}
