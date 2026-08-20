package com.javaclaw.ui.javafx.plugin;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Shared deterministic cleanup for plugin-center child views and async resources. */
final class PluginCenterCleanup {
    private PluginCenterCleanup() { }

    static void closeCards(List<PluginChildView<VBox>> cards, FlowPane grid) {
        RuntimeException failure = null;
        for (int index = cards.size() - 1; index >= 0; index--) {
            try {
                cards.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cards.clear();
        if (grid != null) grid.getChildren().clear();
        if (failure != null) throw failure;
    }

    static RuntimeException closeStep(RuntimeException current, Runnable step) {
        try {
            step.run();
            return current;
        } catch (RuntimeException failure) {
            if (current == null) return failure;
            current.addSuppressed(failure);
            return current;
        }
    }

    static void unbindViewState(
            TextField searchField, Label statusLabel, PluginCenterViewModel viewModel,
            StackPane loadingOverlay, Button refreshButton, Button installButton) {
        if (searchField == null) return;
        searchField.textProperty().unbindBidirectional(viewModel.queryProperty());
        statusLabel.textProperty().unbind();
        statusLabel.visibleProperty().unbind();
        statusLabel.managedProperty().unbind();
        viewModel.loadingProperty().unbind();
        viewModel.mutatingProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        refreshButton.disableProperty().unbind();
        installButton.disableProperty().unbind();
    }

    static String failureMessage(Throwable failure) {
        String detail = null;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current);
             current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                detail = current.getMessage();
            }
        }
        return detail == null ? "未知错误" : detail;
    }
}
