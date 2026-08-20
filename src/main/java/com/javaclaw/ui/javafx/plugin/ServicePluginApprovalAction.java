package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService.ApprovalResult;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.property.ReadOnlyBooleanProperty;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared asynchronous action for approval requests raised by plugin cards and details. */
final class ServicePluginApprovalAction implements AutoCloseable {
    private final PluginManagementApplicationService useCases;
    private final DialogService dialogs;
    private final UiAsyncAction<ApprovalResult> action;
    private final Consumer<Catalog> applyCatalog;
    private final Runnable showServicePlugins;
    private final Consumer<String> showStatus;
    private final BiConsumer<String, Throwable> showFailure;
    private final Runnable refresh;

    ServicePluginApprovalAction(
            PluginManagementApplicationService useCases,
            DialogService dialogs,
            TaskSubmitter tasks,
            FxDispatcher fx,
            Consumer<Catalog> applyCatalog,
            Runnable showServicePlugins,
            Consumer<String> showStatus,
            BiConsumer<String, Throwable> showFailure,
            Runnable refresh) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.action = new UiAsyncAction<>(tasks, fx);
        this.applyCatalog = Objects.requireNonNull(applyCatalog, "applyCatalog");
        this.showServicePlugins = Objects.requireNonNull(showServicePlugins, "showServicePlugins");
        this.showStatus = Objects.requireNonNull(showStatus, "showStatus");
        this.showFailure = Objects.requireNonNull(showFailure, "showFailure");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    ReadOnlyBooleanProperty busyProperty() {
        return action.busyProperty();
    }

    void execute(String pluginId) {
        action.execute(
                TaskSpec.io("service-plugin-approve-" + pluginId),
                context -> useCases.approveServicePlugin(pluginId),
                result -> approvalCompleted(result),
                failure -> {
                    showFailure.accept("服务插件批准失败", failure);
                    dialogs.notify(new ToastRequest("服务插件批准失败",
                            PluginCenterCleanup.failureMessage(failure)));
                    refresh.run();
                });
    }

    private void approvalCompleted(ApprovalResult result) {
        applyCatalog.accept(result.catalog());
        if (!result.approved()) {
            showStatus.accept("未批准，插件仍处于待批准状态");
            return;
        }
        showServicePlugins.run();
        showStatus.accept("服务插件已批准并注册，等待手动启动");
    }

    @Override
    public void close() {
        action.close();
    }
}
