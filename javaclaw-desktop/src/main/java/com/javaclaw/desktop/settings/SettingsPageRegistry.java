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
        register(
                "profiles",
                new AgentProfileSettingsPage(gateway, checked.promptPreview(), checked.promptOptimization()));
        register("learning", new LearningSettingsPage(checked.learning()));
        register("permissions", new PermissionProfileSettingsPage(gateway));
        register("vault", new VaultSettingsPage(gateway));
        register("unattended-grants", new UnattendedToolGrantSettingsPage(gateway));
        register("network-grants", new PrivateNetworkGrantSettingsPage(gateway));
        register("mcp", new McpSettingsPage(checked.mcp()));
        register("connection", new ConnectionSettingsPage(gateway));
        register("diagnostics", new DiagnosticsSettingsPage(gateway));
        register("lifecycle", new LifecycleSettingsPage(gateway));
        register("workspace", new WorkspaceSettingsPage(gateway));
        register("instructions", new InstructionSettingsPage(checked.instructions()));
        register("worktrees", new ManagedWorktreeSettingsPage(gateway));
        register("bundles", new BundleSettingsPage(checked.bundles()));
        register("trust", new TrustKeySettingsPage(checked.bundles()));
        register("trash", new BundleTrashSettingsPage(checked.bundles()));
        register("builtins", new BuiltinExtensionSettingsPage(checked.builtins()));
        register("jobs", new AutomationJobSettingsPage(checked.jobs()));
        register("site", new SiteSettingsPage(gateway, checked.extensions()));
        registerExtension("plan", "Plan", "结构化计划、决策与执行", BuiltinExtensionIds.PLAN, checked.extensions());
        registerExtension("loop", "Loop", "迭代目标、验证和停止条件", BuiltinExtensionIds.LOOP, checked.extensions());
        registerExtension("workflow", "Workflow", "安全 Graph 与持久执行", BuiltinExtensionIds.WORKFLOW, checked.extensions());
        registerExtension("sdd", "SDD", "规格、审批、实现与验收", BuiltinExtensionIds.SDD, checked.extensions());
        registerExtension(
                "schedule", "Schedule", "触发规则、Occurrence 与恢复", BuiltinExtensionIds.SCHEDULE, checked.extensions());
        registerExtension("memory", "Memory", "记忆、来源、历史与提案", BuiltinExtensionIds.MEMORY, checked.extensions());
        registerExtension(
                "knowledge", "Knowledge", "资料、索引 Generation 与检索", BuiltinExtensionIds.KNOWLEDGE, checked.extensions());
        registerExtension("skill", "Skill", "Draft、发布、目录和资源", BuiltinExtensionIds.SKILL, checked.extensions());
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
}
