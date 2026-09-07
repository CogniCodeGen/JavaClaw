package com.javaclaw.desktop.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

/** 管理中心页面注册表；窗口只按 key 路由，不识别领域页面类型。 */
final class SettingsPageRegistry {
    private final Map<String, ManagedSettingsPage> pages = new HashMap<>();

    SettingsPageRegistry(DesktopAppearanceManager appearance, ManagementSettingsGateways gateways, Runnable close) {
        Objects.requireNonNull(appearance, "appearance");
        ManagementSettingsGateways checked = Objects.requireNonNull(gateways, "gateways");
        CoreSettingsGateway gateway = checked.core();
        register("appearance", new AppearanceSettingsPage(appearance, close));
        register("providers", new ProviderSettingsPage(gateway));
        register("roles", new AgentRoleSettingsPage(gateway, checked.promptPreview(), checked.promptOptimization()));
        registerExtension(
                "learning",
                "学习策略",
                "设置记忆学习和技能建议规则",
                BuiltinExtensionIds.MEMORY,
                "memory.learning",
                checked.extensions());
        register("permissions", new PermissionProfileSettingsPage(gateway));
        register("vault", new VaultSettingsPage(gateway));
        register("unattended-grants", new UnattendedToolGrantSettingsPage(gateway, checked.schedules()));
        register("network-grants", new PrivateNetworkGrantSettingsPage(gateway));
        register("mcp", new McpSettingsPage(checked.mcp()));
        register("connection", new ConnectionSettingsPage(gateway));
        register("diagnostics", new DiagnosticsSettingsPage(gateway));
        register("lifecycle", new LifecycleSettingsPage(gateway));
        register("workspace", new WorkspaceSettingsPage(gateway));
        register("coding", new CodingSettingsPage(checked.coding()));
        register("instructions", new InstructionSettingsPage(checked.instructions()));
        register("worktrees", new ManagedWorktreeSettingsPage(gateway));
        register("bundles", new BundleSettingsPage(checked.bundles()));
        register("trust", new TrustKeySettingsPage(checked.bundles()));
        register("trash", new BundleTrashSettingsPage(checked.bundles()));
        register("builtins", new BuiltinExtensionSettingsPage(checked.builtins()));
        register("jobs", new AutomationJobSettingsPage(checked.jobs()));
        register("site", new SiteSettingsPage(gateway, checked.extensions()));
        registerExtension("plan", "计划", "结构化计划、决策与执行", BuiltinExtensionIds.PLAN, checked.extensions());
        registerExtension("loop", "循环任务", "迭代目标、验证和停止条件", BuiltinExtensionIds.LOOP, checked.extensions());
        registerExtension("workflow", "工作流", "安全编排和持久执行", BuiltinExtensionIds.WORKFLOW, checked.extensions());
        registerExtension("sdd", "规格驱动开发（SDD）", "管理规格、审批、实现和验收", BuiltinExtensionIds.SDD, checked.extensions());
        registerExtension("schedule", "定时任务", "管理触发规则、执行记录和失败恢复", BuiltinExtensionIds.SCHEDULE, checked.extensions());
        registerExtension("memory", "记忆", "管理记忆来源、历史和建议", BuiltinExtensionIds.MEMORY, checked.extensions());
        registerExtension("knowledge", "知识库", "管理资料、索引版本和检索", BuiltinExtensionIds.KNOWLEDGE, checked.extensions());
        registerExtension("skill", "技能", "管理草稿、发布、目录和资源", BuiltinExtensionIds.SKILL, checked.extensions());
    }

    ManagedSettingsPage resolve(String key) {
        ManagedSettingsPage page = pages.get(Objects.requireNonNull(key, "key"));
        if (page == null) {
            throw new IllegalArgumentException("未知设置页面: " + key);
        }
        return page;
    }

    void dispose() {
        pages.values().forEach(ManagedSettingsPage::dispose);
        pages.clear();
    }

    private void register(String key, ManagedSettingsPage page) {
        if (pages.putIfAbsent(key, Objects.requireNonNull(page, "page")) != null) {
            throw new IllegalStateException("重复设置页面: " + key);
        }
    }

    private void registerExtension(
            String key,
            String title,
            String description,
            String extensionId,
            ExtensionSettingsGateway extensionGateway) {
        register(key, new ViewSchemaSettingsPage(extensionId, title, description, extensionGateway));
    }

    private void registerExtension(
            String key,
            String title,
            String description,
            String extensionId,
            String preferredViewId,
            ExtensionSettingsGateway extensionGateway) {
        register(key, new ViewSchemaSettingsPage(extensionId, title, description, preferredViewId, extensionGateway));
    }
}
