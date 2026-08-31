package com.javaclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次用户请求的执行快照；终态必须携带完成时间，重试诊断通过 attemptId 关联。
 *
 * @param id Turn 的非空标识
 * @param threadId 所属 Thread 的非空标识
 * @param attemptId 本次执行尝试的非空标识
 * @param status 非空执行状态
 * @param input 用户输入的非空快照列表
 * @param config 本次执行使用的非空 Turn 配置快照
 * @param error 错误摘要；无错误时可为 null
 * @param startedAt 开始时间，非空
 * @param completedAt 完成时间；非终态可为 null，终态不可为空
 */
public record AgentTurn(
        TurnId id,
        ThreadId threadId,
        AttemptId attemptId,
        TurnStatus status,
        List<TurnInput> input,
        TurnConfig config,
        String error,
        Instant startedAt,
        Instant completedAt) {
    /** 复制输入并校验终态与 completedAt 的一致性，防止恢复时出现无完成时间的终态 Turn。 */
    public AgentTurn {
        id = Objects.requireNonNull(id, "id");
        threadId = Objects.requireNonNull(threadId, "threadId");
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        status = Objects.requireNonNull(status, "status");
        input = List.copyOf(Objects.requireNonNull(input, "input"));
        config = Objects.requireNonNull(config, "config");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (status.terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal turn requires completedAt");
        }
    }
}
