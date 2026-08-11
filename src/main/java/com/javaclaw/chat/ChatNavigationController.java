package com.javaclaw.chat;

import com.javaclaw.api.conversation.ActionMode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.settings.SettingsView;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Opens chat-adjacent windows without retaining workspace-scoped services across rebuilds. */
public final class ChatNavigationController {
    private static final Logger log = LoggerFactory.getLogger(ChatNavigationController.class);

    private final ApplicationKernel applicationKernel;
    private final DiagnosticsViewFactory diagnostics;
    private final PluginCenterViewFactory plugins;
    private final Supplier<Stage> owner;
    private final Supplier<ModeRegistry> modes;
    private final Predicate<String> rejectWhileRebuilding;
    private final BooleanSupplier rebuilding;
    private final Runnable rebuildRuntime;
    private final Runnable refreshKnowledgeMenu;

    ChatNavigationController(
            ApplicationKernel applicationKernel,
            DiagnosticsViewFactory diagnostics,
            PluginCenterViewFactory plugins,
            Supplier<Stage> owner,
            Supplier<ModeRegistry> modes,
            Predicate<String> rejectWhileRebuilding,
            BooleanSupplier rebuilding,
            Runnable rebuildRuntime,
            Runnable refreshKnowledgeMenu) {
        this.applicationKernel = Objects.requireNonNull(applicationKernel, "applicationKernel");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.modes = Objects.requireNonNull(modes, "modes");
        this.rejectWhileRebuilding = Objects.requireNonNull(
                rejectWhileRebuilding, "rejectWhileRebuilding");
        this.rebuilding = Objects.requireNonNull(rebuilding, "rebuilding");
        this.rebuildRuntime = Objects.requireNonNull(rebuildRuntime, "rebuildRuntime");
        this.refreshKnowledgeMenu = Objects.requireNonNull(
                refreshKnowledgeMenu, "refreshKnowledgeMenu");
    }

    void openSettings(String category) {
        log.info("打开设置对话框{}", category != null ? "（直达：" + category + "）" : "");
        SettingsView view = applicationKernel.current().settingsViews().create(owner.get());
        view.setOnModelConfigChanged(rebuildRuntime);
        view.show(category);
    }

    void openDiagnostics() { diagnostics.open(owner.get()); }

    void openSkills() {
        log.info("打开技能中心");
        applicationKernel.current().skillViews().create(owner.get()).showAndWait();
    }

    void openMemory() {
        if (rejectWhileRebuilding.test("打开记忆中心")) return;
        log.info("打开记忆中心");
        applicationKernel.current().memoryViews().create(owner.get()).show();
    }

    void openPlugins() {
        log.info("打开插件中心");
        plugins.create(owner.get()).showAndWait();
    }

    void openMcp() {
        log.info("打开 MCP 服务器窗口");
        applicationKernel.current().mcpCenters()
                .createWindow(owner.get(), rebuildRuntime).show();
    }

    void openSchedules() {
        log.info("打开定时任务管理");
        applicationKernel.current().scheduleViews().create(owner.get()).show();
    }

    void openTasks() { openActionMode("task", "任务模式"); }

    void openWorkflows() { openActionMode("workflow-center", "工作流中心模式"); }

    void createTask(String description) {
        log.info("打开任务创建对话框（SDD）");
        applicationKernel.current().sddTaskViews().create(owner.get()).showCreate(description);
    }

    void openKnowledge() {
        if (rejectWhileRebuilding.test("打开知识库")) return;
        log.info("打开知识库中心");
        var view = applicationKernel.current().knowledgeViews().create(
                owner.get(), rebuildRuntime, () -> openSettings("嵌入模型"));
        view.setOnHidden(() -> {
            if (!rebuilding.getAsBoolean()) refreshKnowledgeMenu.run();
        });
        view.show();
    }

    private void openActionMode(String id, String label) {
        log.info("打开{}", label);
        modes.get().getById(id)
                .filter(ActionMode.class::isInstance)
                .map(ActionMode.class::cast)
                .ifPresentOrElse(ActionMode::open,
                        () -> log.warn("未注册{}（id={}）", label, id));
    }
}
