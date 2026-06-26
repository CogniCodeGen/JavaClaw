package com.javaclaw.diagnostics;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * 诊断面板 — 按时间/智能体/事件类型筛选 agent-trace.jsonl，支持一键导出诊断包
 */
public final class DiagnosticsView {

    private DiagnosticsView() {}

    public static void open(Stage owner) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("诊断面板");

        // 筛选控件
        ComboBox<String> rangeBox = new ComboBox<>();
        rangeBox.getItems().addAll("最近 15 分钟", "最近 1 小时", "最近 24 小时", "全部");
        rangeBox.getSelectionModel().select(2);

        TextField agentField = new TextField();
        agentField.setPromptText("智能体（精确匹配）");
        agentField.setPrefWidth(160);

        ComboBox<String> eventBox = new ComboBox<>();
        eventBox.getItems().addAll("全部", "tool_call", "tool_result", "model_call", "error");
        eventBox.getSelectionModel().select(0);

        TextField keywordField = new TextField();
        keywordField.setPromptText("关键字（内容子串匹配）");
        HBox.setHgrow(keywordField, Priority.ALWAYS);

        Button queryBtn = new Button("查询");
        queryBtn.getStyleClass().add("skill-add-btn");

        HBox filterRow = new HBox(8, new Label("时间"), rangeBox,
                new Label("智能体"), agentField,
                new Label("事件"), eventBox,
                keywordField, queryBtn);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setPadding(new Insets(12, 14, 8, 14));

        // 结果列表
        ListView<String> resultList = new ListView<>();
        resultList.setStyle("-fx-font-family: 'JetBrains Mono', 'Menlo', monospace; -fx-font-size: 11px;");

        Label summary = new Label("请点击「查询」加载事件");
        summary.setStyle("-fx-text-fill: -jc-text-muted; -fx-font-size: 12px;");

        queryBtn.setOnAction(e -> {
            String keyword = keywordField.getText();
            String agent = agentField.getText();
            String eventType = "全部".equals(eventBox.getValue()) ? null : eventBox.getValue();
            long since = resolveSince(rangeBox.getValue());
            try {
                List<String> lines = TraceExporter.grep(keyword, agent, eventType, since, 2000);
                resultList.getItems().setAll(lines);
                summary.setText("共 " + lines.size() + " 条事件（上限 2000）");
            } catch (Exception ex) {
                resultList.getItems().clear();
                summary.setText("查询失败：" + ex.getMessage());
            }
        });

        // 导出按钮
        Button exportBtn = new Button("导出诊断包");
        exportBtn.getStyleClass().add("task-edit-btn");
        exportBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存诊断包");
            chooser.setInitialFileName("javaclaw-diagnostics-"
                    + java.time.LocalDate.now() + ".zip");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("诊断 Zip (*.zip)", "*.zip"));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            try {
                long size = TraceExporter.exportTo(target.toPath());
                summary.setText("诊断包已导出 · " + target.getAbsolutePath() + " （" + (size / 1024) + " KB）");
            } catch (Exception ex) {
                summary.setText("导出失败：" + ex.getMessage());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(10, summary, spacer, exportBtn);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 14, 10, 14));
        footer.setStyle("-fx-background-color: -jc-surface-panel; -fx-border-color: -jc-border; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setTop(new VBox(filterRow));
        root.setCenter(resultList);
        root.setBottom(footer);

        Scene scene = new Scene(root, 960, 640);
        var cssUrl = DiagnosticsView.class.getResource("/css/chat.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private static long resolveSince(String range) {
        long now = System.currentTimeMillis();
        return switch (range == null ? "" : range) {
            case "最近 15 分钟" -> now - Duration.ofMinutes(15).toMillis();
            case "最近 1 小时" -> now - Duration.ofHours(1).toMillis();
            case "最近 24 小时" -> now - Duration.ofDays(1).toMillis();
            default -> 0L;
        };
    }
}
