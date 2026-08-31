package com.javaclaw.core.api;

import java.util.List;
import java.util.Objects;

/**
 * 已经服务端解析配置的 Turn 启动命令；不是可供客户端任意扩大权限的请求模型。
 *
 * @param threadId 所属 Thread 的非空标识
 * @param input 用户输入的非空快照列表
 * @param config 本次执行使用的非空 Turn 配置快照
 * @param idempotencyKey 可选幂等键；null 或空白表示无幂等键
 */
public record TurnStartCommand(ThreadId threadId, List<TurnInput> input, TurnConfig config, String idempotencyKey) {
    /** 复制非空输入列表并归一幂等键；空输入拒绝启动，权限仍由运行时校验。 */
    public TurnStartCommand {
        threadId = Objects.requireNonNull(threadId, "threadId");
        input = List.copyOf(Objects.requireNonNull(input, "input"));
        if (input.isEmpty()) {
            throw new IllegalArgumentException("turn input must not be empty");
        }
        config = Objects.requireNonNull(config, "config");
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
    }
}
