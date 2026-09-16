package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import javafx.scene.control.ComboBox;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 页面目录选择器；程序恢复选择不触发用户导航，避免草稿保护重入。 */
final class ViewPageDocumentChoice {
    private final ComboBox<ExtensionRpcContracts.ViewDocument> control;
    private boolean restoring;

    ViewPageDocumentChoice(
            ComboBox<ExtensionRpcContracts.ViewDocument> control,
            String title,
            ViewSchemaWireCodec schemas,
            BiConsumer<ExtensionRpcContracts.ViewDocument, ExtensionRpcContracts.ViewDocument> changed) {
        this.control = control;
        PlatformComponentFactory components = new PlatformComponentFactory();
        control.setMaxWidth(Double.MAX_VALUE);
        control.setAccessibleText(title + "页面选择");
        control.setCellFactory(ignored -> components.detailCell(
                value -> ViewSchemaPageFailures.documentLabel(value, schemas),
                ExtensionRpcContracts.ViewDocument::viewId));
        control.setButtonCell(components.textCell(value -> ViewSchemaPageFailures.documentLabel(value, schemas)));
        control.valueProperty().addListener((observable, previous, selected) -> {
            if (!restoring && selected != null && !selected.equals(previous)) {
                changed.accept(previous, selected);
            }
        });
    }

    void select(ExtensionRpcContracts.ViewDocument selected) {
        restoring = true;
        try {
            control.setValue(selected);
        } finally {
            restoring = false;
        }
    }

    List<ExtensionRpcContracts.ViewDocument> available(
            List<ExtensionRpcContracts.ViewDocument> loaded, String extension, Optional<String> fixedView) {
        var available = List.copyOf(loaded).stream()
                .filter(candidate -> extension.equals(candidate.extensionId()))
                .filter(candidate ->
                        fixedView.isEmpty() || fixedView.orElseThrow().equals(candidate.viewId()))
                .sorted(java.util.Comparator.comparing(ExtensionRpcContracts.ViewDocument::viewId))
                .toList();
        restoring = true;
        try {
            control.getItems().setAll(available);
        } finally {
            restoring = false;
        }
        return available;
    }

    void clear() {
        select(null);
        control.getItems().clear();
        control.setDisable(true);
    }

    ExtensionRpcContracts.ViewDocument preferred(
            List<ExtensionRpcContracts.ViewDocument> available,
            ExtensionRpcContracts.ViewDocument current,
            Optional<String> preferred) {
        return Optional.ofNullable(current)
                .map(ExtensionRpcContracts.ViewDocument::viewId)
                .or(() -> preferred)
                .flatMap(viewId -> available.stream()
                        .filter(value -> value.viewId().equals(viewId))
                        .findFirst())
                .orElse(available.getFirst());
    }
}
