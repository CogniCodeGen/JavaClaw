package com.javaclaw.core.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.sandbox.api.SandboxPaths;
import com.javaclaw.sandbox.api.SandboxPolicy;

/**
 * Immutable configuration snapshot captured when a turn starts.
 *
 * @param model 非空白模型标识
 * @param provider 非空白云模型 Provider 标识
 * @param reasoningEffort 推理强度；null 或空白归一为 medium
 * @param workingDirectory 规范化后的工作目录；非空，解析已存在祖先的符号链接
 * @param sandboxPolicy 非空、由服务端收窄后的沙箱策略
 * @param approvalPolicy 审批策略；null 归一为 ON_RISK
 * @param enabledTools 允许选择的工具名集合；null 归一为空集合并复制
 * @param attributes 扩展配置快照；null 归一为空 Map
 */
public record TurnConfig(
        String model,
        String provider,
        String reasoningEffort,
        Path workingDirectory,
        SandboxPolicy sandboxPolicy,
        ApprovalPolicy approvalPolicy,
        Set<String> enabledTools,
        Map<String, String> attributes) {
    /** 校验模型和目录，复制工具/属性并填充默认审批及推理选项；不接受运行中改写此快照。 */
    public TurnConfig {
        model = ThreadId.required(model, "model");
        provider = ThreadId.required(provider, "provider");
        reasoningEffort = reasoningEffort == null ? "medium" : reasoningEffort.strip();
        workingDirectory = SandboxPaths.canonicalize(workingDirectory);
        sandboxPolicy = Objects.requireNonNull(sandboxPolicy, "sandboxPolicy");
        approvalPolicy = approvalPolicy == null ? ApprovalPolicy.ON_RISK : approvalPolicy;
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
