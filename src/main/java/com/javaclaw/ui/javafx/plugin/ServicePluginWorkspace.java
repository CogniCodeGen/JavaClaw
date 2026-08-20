package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Coordinates the service-plugin catalog and its full-width configuration page. */
final class ServicePluginWorkspace implements AutoCloseable {
    private final ServicePluginPanel panel;
    private final ServicePluginConfigurationController configuration;

    ServicePluginWorkspace(
            ServicePluginManagementApplicationService services,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            VBox listView,
            VBox list,
            Label count,
            ServicePluginConfigurationController configuration,
            Consumer<String> status,
            BiConsumer<String, Throwable> failures,
            Runnable missing) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        panel = new ServicePluginPanel(services, tasks, fx, listView, list, count,
                status, failures, pluginId -> open(pluginId, null));
        this.configuration.configure(this::showList, missing, status, failures);
    }

    void setOnRuntimeConfigurationChanged(Runnable callback) {
        configuration.setOnRuntimeConfigurationChanged(callback);
    }

    ObservableBooleanValue busyProperty() {
        return javafx.beans.binding.Bindings.or(
                panel.busyProperty(), configuration.busyProperty());
    }

    void showList() {
        configuration.hide();
        panel.show();
    }

    void open(String pluginId, String pageId) {
        panel.hide();
        configuration.show(pluginId, pageId);
    }

    void hide() {
        panel.hide();
        configuration.hide();
    }

    void refresh() {
        if (configuration.isShowing()) configuration.refreshPage();
        else panel.refresh();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try { configuration.close(); }
        catch (RuntimeException closeFailure) { failure = closeFailure; }
        try { panel.close(); }
        catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
