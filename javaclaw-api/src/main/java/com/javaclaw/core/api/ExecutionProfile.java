package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.sandbox.api.SandboxMode;

/**
 * Versioned, user-visible execution policy resolved to a Turn snapshot at start time.
 *
 * @param id 非空白 Profile 标识
 * @param name 非空白展示名称
 * @param kind 执行场景，非空
 * @param provider 非空白云模型 Provider 标识
 * @param model 非空白模型标识
 * @param systemPrompt 系统提示词；null 归一为空字符串
 * @param enabledTools 允许选择的工具名集合；null 归一为空集合并复制
 * @param requestedSandboxMode 请求的权限上限；PLAN 只能为 READ_ONLY，非空
 * @param maxIterations 迭代预算，范围 0 到 1000；0 继承模式的有限上限
 * @param maxModelCalls 模型调用预算，范围 0 到 1000；0 继承模式的有限上限
 * @param attributes 扩展配置快照；null 归一为空 Map
 * @param revision 从 1 开始的资源修订号，用于乐观锁
 * @param updatedAt 最近更新时间，非空
 */
public record ExecutionProfile(
        String id,
        String name,
        ProfileKind kind,
        String provider,
        String model,
        String systemPrompt,
        Set<String> enabledTools,
        SandboxMode requestedSandboxMode,
        int maxIterations,
        int maxModelCalls,
        Map<String, String> attributes,
        long revision,
        Instant updatedAt) {
    /** 校验版本与预算并复制工具/属性快照；拒绝 PLAN 的写权限，避免配置层提升只读计划能力。 */
    public ExecutionProfile {
        id = ThreadId.required(id, "id");
        name = ThreadId.required(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        provider = ThreadId.required(provider, "provider");
        model = ThreadId.required(model, "model");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
        requestedSandboxMode = Objects.requireNonNull(requestedSandboxMode, "requestedSandboxMode");
        if (kind == ProfileKind.PLAN && requestedSandboxMode != SandboxMode.READ_ONLY) {
            throw new IllegalArgumentException("PLAN profiles must be read-only");
        }
        if (maxIterations < 0 || maxIterations > 1_000) {
            throw new IllegalArgumentException("maxIterations must be between 0 and 1000");
        }
        if (maxModelCalls < 0 || maxModelCalls > 1_000) {
            throw new IllegalArgumentException("maxModelCalls must be between 0 and 1000");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
