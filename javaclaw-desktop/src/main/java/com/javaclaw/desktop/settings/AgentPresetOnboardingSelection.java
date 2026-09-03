package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ProviderRef;

/**
 * 首次智能体向导中尚未全部持久化的精确用户选择。
 *
 * @param defaultProvider 默认智能体的精确 Chat ProviderRef
 * @param workerProvider Worker 的精确 Chat ProviderRef
 * @param explorerProvider Explorer 的精确 Chat ProviderRef
 * @param reviewTools 只读审阅权限和 Explorer 可见的精确工具名
 * @param developerTools 开发权限及 default/worker 可见的精确工具名
 */
public record AgentPresetOnboardingSelection(
        Optional<ProviderRef> defaultProvider,
        Optional<ProviderRef> workerProvider,
        Optional<ProviderRef> explorerProvider,
        Set<String> reviewTools,
        Set<String> developerTools) {
    /** 复制选择并拒绝通配符。 */
    public AgentPresetOnboardingSelection {
        defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider");
        workerProvider = Objects.requireNonNull(workerProvider, "workerProvider");
        explorerProvider = Objects.requireNonNull(explorerProvider, "explorerProvider");
        reviewTools = exactTools(reviewTools, "reviewTools");
        developerTools = exactTools(developerTools, "developerTools");
    }

    /** @return 无模型、无工具选择的初始值 */
    public static AgentPresetOnboardingSelection empty() {
        return new AgentPresetOnboardingSelection(
                Optional.empty(), Optional.empty(), Optional.empty(), Set.of(), Set.of());
    }

    /**
     * 替换一个 Profile 预设使用的精确模型。
     *
     * @param presetId default、worker 或 explorer
     * @param provider 精确 Chat ProviderRef
     * @return 新选择
     */
    public AgentPresetOnboardingSelection withProvider(String presetId, ProviderRef provider) {
        ProviderRef checked = Objects.requireNonNull(provider, "provider");
        return switch (Objects.requireNonNull(presetId, "presetId")) {
            case "default" ->
                new AgentPresetOnboardingSelection(
                        Optional.of(checked), workerProvider, explorerProvider, reviewTools, developerTools);
            case "worker" ->
                new AgentPresetOnboardingSelection(
                        defaultProvider, Optional.of(checked), explorerProvider, reviewTools, developerTools);
            case "explorer" ->
                new AgentPresetOnboardingSelection(
                        defaultProvider, workerProvider, Optional.of(checked), reviewTools, developerTools);
            default -> throw new IllegalArgumentException("不支持的智能体预设: " + presetId);
        };
    }

    /**
     * 替换一个权限预设使用的精确工具集合。
     *
     * @param presetId workspace-review 或 workspace-developer
     * @param tools 用户显式选择的工具名
     * @return 新选择
     */
    public AgentPresetOnboardingSelection withTools(String presetId, Set<String> tools) {
        return switch (Objects.requireNonNull(presetId, "presetId")) {
            case "workspace-review" ->
                new AgentPresetOnboardingSelection(
                        defaultProvider, workerProvider, explorerProvider, tools, developerTools);
            case "workspace-developer" ->
                new AgentPresetOnboardingSelection(
                        defaultProvider, workerProvider, explorerProvider, reviewTools, tools);
            default -> throw new IllegalArgumentException("不支持的权限预设: " + presetId);
        };
    }

    /**
     * 返回一个 Profile 预设选择的精确模型。
     *
     * @param presetId default、worker 或 explorer
     * @return 可空选择
     */
    public Optional<ProviderRef> provider(String presetId) {
        return switch (Objects.requireNonNull(presetId, "presetId")) {
            case "default" -> defaultProvider;
            case "worker" -> workerProvider;
            case "explorer" -> explorerProvider;
            default -> throw new IllegalArgumentException("不支持的智能体预设: " + presetId);
        };
    }

    /**
     * 返回一个权限预设选择的精确工具集合。
     *
     * @param presetId workspace-review 或 workspace-developer
     * @return 不可变工具名集合
     */
    public Set<String> tools(String presetId) {
        return switch (Objects.requireNonNull(presetId, "presetId")) {
            case "workspace-review" -> reviewTools;
            case "workspace-developer" -> developerTools;
            default -> throw new IllegalArgumentException("不支持的权限预设: " + presetId);
        };
    }

    /** @return 三个 Profile 是否都已选择精确模型 */
    public boolean modelsComplete() {
        return defaultProvider.isPresent() && workerProvider.isPresent() && explorerProvider.isPresent();
    }

    private static Set<String> exactTools(Set<String> tools, String name) {
        Set<String> copied = Set.copyOf(Objects.requireNonNull(tools, name));
        if (copied.stream().anyMatch(tool -> tool.isBlank() || tool.indexOf('*') >= 0)) {
            throw new IllegalArgumentException("工具选择必须使用非空精确名称，不能包含通配符");
        }
        return copied;
    }
}
