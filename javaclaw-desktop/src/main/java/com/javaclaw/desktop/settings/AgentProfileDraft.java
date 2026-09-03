package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;

/**
 * Agent Profile 表单的不可变草稿。
 *
 * @param id 稳定 Profile 标识
 * @param displayName 用户可见名称
 * @param systemInstruction Profile 系统说明
 * @param provider 精确 Provider/model 引用
 * @param permissionProfile 精确 PermissionProfile 引用
 * @param visibleTools 逗号或换行分隔的工具可见上限
 * @param inputTokens 最大输入 token
 * @param outputTokens 最大输出 token
 * @param toolCalls 最大工具调用次数
 * @param childThreads 最大直接子 Thread 数
 * @param wallTimeSeconds 墙钟预算秒数
 * @param lifecycle 生命周期
 */
public record AgentProfileDraft(
        String id,
        String displayName,
        String systemInstruction,
        Optional<ProviderRef> provider,
        Optional<PermissionProfileRef> permissionProfile,
        String visibleTools,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        int childThreads,
        long wallTimeSeconds,
        ProfileLifecycle lifecycle) {
    /** 保留尚未通过领域校验的用户输入并复制引用。 */
    public AgentProfileDraft {
        id = Objects.requireNonNullElse(id, "");
        displayName = Objects.requireNonNullElse(displayName, "");
        systemInstruction = Objects.requireNonNullElse(systemInstruction, "");
        provider = Objects.requireNonNull(provider, "provider");
        permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
        visibleTools = Objects.requireNonNullElse(visibleTools, "");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** @return 新建 Profile 的初始草稿 */
    public static AgentProfileDraft empty() {
        return new AgentProfileDraft(
                "", "", "", Optional.empty(), Optional.empty(), "", 32_000, 4_000, 16, 1, 300, ProfileLifecycle.ACTIVE);
    }

    /**
     * 从权威 Profile 生成草稿。
     *
     * @param profile Profile 快照
     * @return 等价草稿
     */
    public static AgentProfileDraft from(AgentProfile profile) {
        AgentProfile checked = Objects.requireNonNull(profile, "profile");
        AgentProfileSpec spec = checked.spec();
        TurnBudget budget = spec.budget();
        return new AgentProfileDraft(
                checked.id(),
                spec.displayName(),
                spec.systemInstruction(),
                Optional.of(spec.provider()),
                Optional.of(spec.permissionProfile()),
                spec.visibleTools().stream().sorted().collect(Collectors.joining("\n")),
                budget.inputTokens(),
                budget.outputTokens(),
                budget.toolCalls(),
                budget.childThreads(),
                budget.wallTime().toSeconds(),
                checked.lifecycle());
    }

    /**
     * 转换为完整领域配置。
     *
     * @return 已通过 AgentProfileSpec 校验的配置
     */
    public AgentProfileSpec toSpec() {
        Set<String> tools = Arrays.stream(visibleTools.split("[,\\n]"))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        TurnBudget budget =
                new TurnBudget(inputTokens, outputTokens, toolCalls, childThreads, Duration.ofSeconds(wallTimeSeconds));
        return new AgentProfileSpec(
                displayName,
                systemInstruction,
                provider.orElseThrow(() -> new IllegalArgumentException("请选择模型服务和模型")),
                permissionProfile.orElseThrow(() -> new IllegalArgumentException("请选择权限方案")),
                tools,
                budget);
    }
}
