package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.WorkspaceId;

/**
 * 设置与管理中心的强类型 SDK 边界集合。
 *
 * <p>该值对象只负责组合页面网关，不暴露传输层或服务端实现。
 *
 * @param core 平台核心设置
 * @param agentOnboarding 内置智能体首次初始化
 * @param promptPreview Prompt provenance
 * @param promptOptimization Prompt 优化草稿与人工采纳
 * @param mcp MCP Host 设置
 * @param instructions 项目约定摘要
 * @param bundles 第三方 Bundle、信任和 Trash
 * @param builtins 内置 Bundle 与 MCP 平台能力
 * @param jobs 全局可恢复 Extension Job
 * @param schedules Schedule 精确定义目录
 * @param extensions ViewSchema v2 扩展页面
 * @param preferredWorkspace 设置中心首次打开时的 Workspace 快照；后续主窗口切换不会改动已冻结作用域
 */
public record ManagementSettingsGateways(
        CoreSettingsGateway core,
        AgentPresetOnboardingGateway agentOnboarding,
        PromptPreviewSettingsGateway promptPreview,
        PromptOptimizationSettingsGateway promptOptimization,
        McpSettingsGateway mcp,
        InstructionSettingsGateway instructions,
        BundleSettingsGateway bundles,
        BuiltinExtensionSettingsGateway builtins,
        AutomationJobSettingsGateway jobs,
        ScheduleCatalogGateway schedules,
        ExtensionSettingsGateway extensions,
        Supplier<Optional<WorkspaceId>> preferredWorkspace) {
    /** 校验所有页面边界均已装配。 */
    public ManagementSettingsGateways {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(agentOnboarding, "agentOnboarding");
        Objects.requireNonNull(promptPreview, "promptPreview");
        Objects.requireNonNull(promptOptimization, "promptOptimization");
        Objects.requireNonNull(mcp, "mcp");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(bundles, "bundles");
        Objects.requireNonNull(builtins, "builtins");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(preferredWorkspace, "preferredWorkspace");
    }
}
