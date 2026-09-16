package com.javaclaw.desktop.shell;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.state.OutgoingMessage;

/** 原生简版消息卡片；展开状态由页面拥有，Cell 回收不会丢失选择，操作只携带当前行身份。 */
final class ShellSummaryCell extends ListCell<ShellTranscriptRow> {
    private final Label title = new Label();
    private final Label body = new Label();
    private final Hyperlink open = link("查看完整消息");
    private final Hyperlink disclosure = link("展开详情");
    private TextArea details;
    private final VBox files = new VBox(4);
    private final HBox actions = new HBox(8);
    private final VBox card = new VBox(6, title, body, disclosure, open, files, actions);
    private final Consumer<DocumentReference> preview;
    private final Supplier<Optional<WorkspaceId>> workspace;
    private final Predicate<String> expanded;

    ShellSummaryCell(
            Consumer<DocumentReference> preview,
            Supplier<Optional<WorkspaceId>> workspace,
            Predicate<String> expanded,
            Consumer<String> toggle,
            BiConsumer<String, String> outgoing,
            ReadOnlyBooleanProperty retryReady,
            ReadOnlyBooleanProperty restoreAllowed) {
        this.preview = preview;
        this.workspace = workspace;
        this.expanded = expanded;
        title.getStyleClass().add("message-role");
        disclosure.getStyleClass().add("tool-details-toggle");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        card.setMaxWidth(960);
        card.prefWidthProperty().bind(widthProperty().subtract(2));
        open.setOnAction(event -> getItem().full().ifPresent(preview));
        disclosure.setOnAction(event -> {
            if (getItem() != null) {
                toggle.accept(getItem().id());
                renderDisclosure(getItem());
            }
        });
        Button retry = action("重试", "retrySend", outgoing);
        retry.disableProperty().bind(retryReady.not());
        Button restore = action("恢复到输入框", "restoreSend", outgoing);
        restore.disableProperty().bind(restoreAllowed.not());
        restore.setTooltip(new Tooltip("输入框为空时可恢复；也可复制原文"));
        actions.getChildren().addAll(retry, restore, action("复制原文", "copySend", outgoing));
    }

    private Button action(String label, String action, BiConsumer<String, String> handler) {
        Button button = new Button(label);
        button.getStyleClass().add("sidebar-manage-btn");
        button.setOnAction(event -> {
            if (getItem() != null) {
                getItem().outgoing().ifPresent(message -> handler.accept(action, message.id()));
            }
        });
        return button;
    }

    @Override
    protected void updateItem(ShellTranscriptRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            removeDetails();
            setGraphic(null);
            return;
        }
        title.setText(row.title());
        body.setText(bounded(row.body()));
        visible(body, !row.body().isEmpty());
        card.getStyleClass().setAll("message-bubble", row.presented().styleClass());
        visible(open, row.full().isPresent());
        renderDisclosure(row);
        files.getChildren().clear();
        row.source().ifPresent(item -> {
            workspace
                    .get()
                    .ifPresent(scope -> item.attachments()
                            .forEach(attachment -> addFile(
                                    attachment.fileName(),
                                    DocumentReference.attachment(scope, item.id(), attachment))));
            item.fileReferences().forEach(reference -> addFile("查看引用文件", reference));
        });
        workspace
                .get()
                .ifPresent(scope -> row.presented()
                        .attachments()
                        .forEach(attachment -> addFile(
                                attachment.fileName(),
                                DocumentReference.attachment(scope, ItemId.parse(row.id()), attachment))));
        visible(
                actions,
                row.outgoing()
                        .filter(value -> value.status() == OutgoingMessage.Status.UNCONFIRMED)
                        .isPresent());
        setGraphic(card);
    }

    private void renderDisclosure(ShellTranscriptRow row) {
        boolean showing = row.presented().collapsible() && expanded.test(row.id());
        visible(disclosure, row.presented().collapsible());
        disclosure.setText(showing ? "收起详情" : "展开详情");
        disclosure.setAccessibleText(disclosure.getText() + "：" + row.title());
        if (showing) {
            if (details == null) {
                details = new TextArea();
                details.setEditable(false);
                details.setWrapText(true);
                details.setPrefHeight(180);
                details.getStyleClass().addAll("code-view", "tool-details-body");
                card.getChildren().add(3, details);
            }
            details.setText(bounded(row.presented().details()));
        } else {
            removeDetails();
        }
    }

    private void removeDetails() {
        if (details != null) {
            // 普通消息和折叠行不保留 TextArea 的 Skin、滚动条与编辑缓存，避免虚拟列表付出嵌套控件成本。
            visible(details, false);
            card.getChildren().remove(details);
            details = null;
        }
    }

    private void addFile(String label, DocumentReference reference) {
        Hyperlink hyperlink = link(label);
        hyperlink.setOnAction(event -> preview.accept(reference));
        files.getChildren().add(hyperlink);
    }

    private static String bounded(String text) {
        if (text.length() <= 65_536) {
            return text;
        }
        int end = Character.isLowSurrogate(text.charAt(65_536)) ? 65_535 : 65_536;
        return text.substring(0, end) + "\n[内容较长，当前显示已截断]";
    }

    private static Hyperlink link(String label) {
        Hyperlink hyperlink = new Hyperlink(label);
        hyperlink.getStyleClass().add("transcript-document-link");
        return hyperlink;
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
