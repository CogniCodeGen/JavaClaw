package com.javaclaw.ui.javafx.memory;

import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Persona;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 记忆中心 —— 查看 / 编辑 / 审计 EclipseStore 记忆库（补偿去文件化后的可读性）。
 *
 * <p>三页签：</p>
 * <ul>
 *   <li><b>事实</b>：列出语义事实，可编辑（重嵌入 + 置用户保护位）/删除</li>
 *   <li><b>人格</b>：编辑人格正文（替代 AGENTS.md 文件）</li>
 *   <li><b>变更日志</b>：append-only 审计轨（替代备份）</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class MemoryCenterView {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage stage;
    private final MemoryService svc;

    private final TableView<Fact> factTable = new TableView<>();
    private final TableView<ChangeLogEntry> logTable = new TableView<>();
    private final TextArea personaArea = new TextArea();

    public MemoryCenterView(Stage owner, MemoryService svc) {
        this.svc = svc;
        this.stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("记忆中心");

        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(buildFactsTab(), buildPersonaTab(), buildLogTab());
        tabs.getStyleClass().add("modal-content-area");

        Scene scene = new Scene(tabs, 760, 520);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        refreshAll();
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    // ==================== 事实页 ====================

    @SuppressWarnings("unchecked")
    private Tab buildFactsTab() {
        TableColumn<Fact, String> sec = new TableColumn<>("主题");
        sec.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().section == null ? "" : c.getValue().section));
        sec.setPrefWidth(110);
        TableColumn<Fact, String> txt = new TableColumn<>("事实");
        txt.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().text));
        txt.setPrefWidth(380);
        TableColumn<Fact, String> upd = new TableColumn<>("更新");
        upd.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(fmt(c.getValue().updatedAt)));
        upd.setPrefWidth(120);
        TableColumn<Fact, String> flag = new TableColumn<>("用户改");
        flag.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().userEdited ? "✓" : ""));
        flag.setPrefWidth(60);
        factTable.getColumns().setAll(sec, txt, upd, flag);
        factTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Button edit = new Button("编辑");
        edit.getStyleClass().add("jc-btn");
        edit.setOnAction(e -> editSelectedFact());
        Button del = new Button("删除");
        del.getStyleClass().add("jc-btn");
        del.setOnAction(e -> deleteSelectedFact());
        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("jc-btn");
        refresh.setOnAction(e -> refreshFacts());
        HBox bar = new HBox(8, edit, del, refresh);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("modal-foot");

        BorderPane pane = new BorderPane(factTable);
        pane.setBottom(bar);
        Tab tab = new Tab("事实", pane);
        tab.setClosable(false);
        return tab;
    }

    private void editSelectedFact() {
        Fact f = factTable.getSelectionModel().getSelectedItem();
        if (f == null) return;
        TextInputDialog dlg = new TextInputDialog(f.text);
        dlg.initOwner(stage);
        dlg.setTitle("编辑事实");
        dlg.setHeaderText("修改后将重新嵌入并标记为用户编辑（不再被自动蒸馏覆盖）");
        Optional<String> r = dlg.showAndWait();
        if (r.isPresent() && !r.get().isBlank() && !r.get().equals(f.text)) {
            svc.editFact(f, r.get().trim());
            refreshFacts();
        }
    }

    private void deleteSelectedFact() {
        Fact f = factTable.getSelectionModel().getSelectedItem();
        if (f == null) return;
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "删除事实：" + f.text + " ?", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            svc.deleteFact(f);
            refreshFacts();
        }
    }

    // ==================== 人格页 ====================

    private Tab buildPersonaTab() {
        personaArea.setWrapText(true);
        VBox.setVgrow(personaArea, Priority.ALWAYS);
        Button save = new Button("保存人格");
        save.getStyleClass().add("jc-btn");
        save.setOnAction(e -> {
            svc.setPersona(personaArea.getText(), "user");
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                    "人格已保存（下一轮对话生效）", ButtonType.OK).showAndWait();
        });
        HBox bar = new HBox(save);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("modal-foot");
        VBox box = new VBox(8, new Label("人格正文（每轮注入系统提示词，替代 AGENTS.md）："), personaArea, bar);
        box.setPadding(new Insets(10));
        Tab tab = new Tab("人格", box);
        tab.setClosable(false);
        return tab;
    }

    // ==================== 变更日志页 ====================

    private Tab buildLogTab() {
        TableColumn<ChangeLogEntry, String> ts = new TableColumn<>("时间");
        ts.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(fmt(c.getValue().timestamp)));
        ts.setPrefWidth(120);
        TableColumn<ChangeLogEntry, String> op = new TableColumn<>("操作");
        op.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().op));
        op.setPrefWidth(90);
        TableColumn<ChangeLogEntry, String> ty = new TableColumn<>("类型");
        ty.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().type));
        ty.setPrefWidth(120);
        TableColumn<ChangeLogEntry, String> de = new TableColumn<>("详情");
        de.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().detail));
        de.setPrefWidth(380);
        logTable.getColumns().setAll(List.of(ts, op, ty, de));
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("jc-btn");
        refresh.setOnAction(e -> refreshLog());
        HBox bar = new HBox(refresh);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("modal-foot");
        BorderPane pane = new BorderPane(logTable);
        pane.setBottom(bar);
        Tab tab = new Tab("变更日志", pane);
        tab.setClosable(false);
        return tab;
    }

    // ==================== 刷新 ====================

    private void refreshAll() {
        refreshFacts();
        refreshLog();
        Persona p = svc.getPersona();
        personaArea.setText(p != null && p.content != null ? p.content : "");
    }

    private void refreshFacts() {
        factTable.setItems(FXCollections.observableArrayList(svc.facts()));
    }

    private void refreshLog() {
        logTable.setItems(FXCollections.observableArrayList(svc.recentChangeLog(500)));
    }

    private static String fmt(long epochMs) {
        if (epochMs <= 0) return "";
        return TS_FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
