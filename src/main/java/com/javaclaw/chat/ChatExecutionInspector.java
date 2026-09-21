package com.javaclaw.chat;

import com.javaclaw.agent.ChatService;
import com.javaclaw.framework.api.AgentStep;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Window;

/** Expandable read-only view of the durable Thread → Turn → Step hierarchy. */
final class ChatExecutionInspector {
    private ChatExecutionInspector() { }

    static void show(Window owner, ChatService service, String sessionId) {
        TreeItem<String> thread = new TreeItem<>("会话 " + sessionId);
        thread.setExpanded(true);
        for (var turn : service.sessionTurns(sessionId)) {
            TreeItem<String> item = new TreeItem<>("轮次 " + turn.id() + " · " + turn.state());
            thread.getChildren().add(item);
            item.getChildren().add(new TreeItem<>("时间：" + turn.createdAt() + " → " + turn.updatedAt()));
            if (turn.output() != null) item.getChildren().add(new TreeItem<>("轮次结果：\n" + value(turn.output())));
            var steps = service.sessionSteps(sessionId, turn.id());
            if (steps.isEmpty()) item.getChildren().add(new TreeItem<>("此轮没有可用的原子步骤记录"));
            for (AgentStep step : steps) {
                TreeItem<String> atomic = new TreeItem<>(step.kind() + " · " + step.state()
                        + " · " + step.id());
                atomic.getChildren().add(new TreeItem<>("输入：" + value(step.input())));
                atomic.getChildren().add(new TreeItem<>("输出：" + value(step.output())));
                atomic.getChildren().add(new TreeItem<>("用量：" + value(step.usage())));
                if (step.causationStepId() != null) atomic.getChildren().add(new TreeItem<>("来源步骤：" + step.causationStepId()));
                if (step.error() != null) atomic.getChildren().add(new TreeItem<>("错误：" + step.error()));
                item.getChildren().add(atomic);
            }
        }
        TreeView<String> tree = new TreeView<>(thread);
        tree.setCellFactory(ignored -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.length() > 140 ? item.substring(0, 140) + "…" : item);
            }
        });
        javafx.scene.control.TextArea details = new javafx.scene.control.TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPromptText("选择步骤输入、输出或来源，查看并复制完整记录");
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) ->
                details.setText(selected == null ? "" : selected.getValue()));
        var content = new javafx.scene.control.SplitPane(tree, details);
        content.setPrefSize(1000, 600);
        content.setDividerPositions(0.45);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("会话执行记录");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private static String value(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null ? "—" : node.toPrettyString();
    }
}
