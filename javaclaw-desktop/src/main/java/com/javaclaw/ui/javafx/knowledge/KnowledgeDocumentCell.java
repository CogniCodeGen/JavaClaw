package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Virtualized document row; reuse updates data only and never reloads FXML. */
public final class KnowledgeDocumentCell extends ListCell<Document> {
    private final Consumer<Document> selection;
    private final BiConsumer<Document, Boolean> toggle;
    private boolean updating;
    @FXML private HBox root;
    @FXML private ToggleSwitch enabledToggle;
    @FXML private Label typeBadge;
    @FXML private Label nameLabel;
    @FXML private Label scopeLabel;
    @FXML private Label summaryLabel;
    @FXML private Label chunksLabel;
    @FXML private Label timeLabel;

    KnowledgeDocumentCell(
            Consumer<Document> selection,
            BiConsumer<Document, Boolean> toggle) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.toggle = Objects.requireNonNull(toggle, "toggle");
        HBox loaded = EmbeddedFxmlLoader.load(
                KnowledgeDocumentCell.class.getResource(
                        "/fxml/knowledge/knowledge-document-cell.fxml"),
                this, HBox.class);
        if (loaded != root) throw new IllegalStateException("知识文档 Cell FXML 根节点不一致");
        enabledToggle.selectedProperty().addListener((ignored, previous, enabled) -> {
            Document item = getItem();
            if (!updating && item != null && !isEmpty()) toggle.accept(item, enabled);
        });
        setOnMouseClicked(this::activate);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                Document item = getItem();
                if (!isEmpty() && item != null) selection.accept(item);
                event.consume();
            }
        });
        selectedProperty().addListener((ignored, previous, value) -> selectedStyle(value));
    }

    @Override
    protected void updateItem(Document document, boolean empty) {
        super.updateItem(document, empty);
        setText(null);
        if (empty || document == null) {
            setGraphic(null);
            return;
        }
        updating = true;
        enabledToggle.setSelected(document.enabled());
        updating = false;
        String extension = extension(document.name());
        typeBadge.setText(extension.toUpperCase(Locale.ROOT));
        typeBadge.getStyleClass().removeAll(
                "kc-type-pdf", "kc-type-md", "kc-type-csv", "kc-type-txt");
        typeBadge.getStyleClass().add(typeStyle(extension));
        nameLabel.setText(document.name());
        scopeLabel.setText(document.scope() == Scope.GLOBAL ? "全局" : "工作区");
        scopeLabel.getStyleClass().removeAll("kc-scope-global", "kc-scope-workspace");
        scopeLabel.getStyleClass().add(document.scope() == Scope.GLOBAL
                ? "kc-scope-global" : "kc-scope-workspace");
        summaryLabel.setText(document.summary());
        summaryLabel.setVisible(!document.summary().isBlank());
        summaryLabel.setManaged(summaryLabel.isVisible());
        chunksLabel.setText(Integer.toString(document.chunkCount()));
        timeLabel.setText(shortDate(document.importedAt()));
        root.setOpacity(document.enabled() ? 1.0 : 0.65);
        selectedStyle(isSelected());
        setAccessibleText(document.name() + "，" + scopeLabel.getText() + "，"
                + document.chunkCount() + " 个片段，"
                + (document.enabled() ? "参与检索" : "不参与检索"));
        setGraphic(root);
    }

    private void activate(MouseEvent event) {
        if (inside(event.getPickResult().getIntersectedNode(), enabledToggle)) return;
        Document item = getItem();
        if (!isEmpty() && item != null) selection.accept(item);
    }

    private void selectedStyle(boolean selected) {
        root.getStyleClass().remove("kc-doc-row-selected");
        if (selected) root.getStyleClass().add("kc-doc-row-selected");
    }

    private static boolean inside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static String extension(String name) {
        int index = name == null ? -1 : name.lastIndexOf('.');
        return index < 0 || index == name.length() - 1 ? "TXT" : name.substring(index + 1);
    }

    private static String typeStyle(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "kc-type-pdf";
            case "md", "markdown" -> "kc-type-md";
            case "csv" -> "kc-type-csv";
            default -> "kc-type-txt";
        };
    }

    private static String shortDate(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }
}
