package com.javaclaw.sdk.model;

import java.time.Instant;
import java.util.List;

/**
 * Turn 的持久执行状态与已解析配置快照。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param status 服务端生命周期或执行结果状态
 * @param attemptId 内部执行尝试标识，仅用于诊断
 * @param input 用户输入 JSON 列表；构造时复制
 * @param config 服务端解析后的配置快照，不应包含明文凭据
 * @param error 脱敏错误摘要；无错误时可为 null
 * @param startedAt Turn 开始时间
 * @param completedAt Turn 完成时间；非终态可为 null
 */
public record TurnInfo(
        String id,
        String threadId,
        String status,
        String attemptId,
        List<JsonDocument> input,
        JsonDocument config,
        String error,
        Instant startedAt,
        Instant completedAt) {
    /** 复制用户输入集合；保留状态、时间和配置的服务端表示。 */
    public TurnInfo {
        input = input == null ? List.of() : List.copyOf(input);
    }
}
