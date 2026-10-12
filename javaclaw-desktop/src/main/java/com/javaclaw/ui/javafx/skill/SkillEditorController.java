package com.javaclaw.ui.javafx.skill;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.OperationResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.UpdateSkillCommand;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 技能编辑器 Controller：只协调表单、确认、应用用例和脚本分区。 */
public final class SkillEditorController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private TextField nameField;
    @FXML private TextField descriptionField;
    @FXML private TextField categoryField;
    @FXML private TextField tagsField;
    @FXML private TextArea contentEditor;
    @FXML private ToggleSwitch enabledToggle;
    @FXML private Label versionLabel;
    @FXML private Label sourceLabel;
    @FXML private Label usageLabel;
    @FXML private ComboBox<String> historyCombo;
    @FXML private Label directoryStructureLabel;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private StackPane loadingOverlay;
    @FXML private SkillScriptController scriptController;

    private final SkillManagementApplicationService useCases;
    private final DialogService dialogs;
    private final ExternalDirectoryOpener directoryOpener;
    private final UiAsyncAction<OperationResult> mutationAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<OperationResult> changed = ignored -> {};
    private SkillDetail detail;

    public SkillEditorController(
            SkillManagementApplicationService useCases,
            DialogService dialogs,
            ExternalDirectoryOpener directoryOpener,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.directoryOpener = Objects.requireNonNull(directoryOpener, "directoryOpener");
        mutationAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        loadingOverlay.visibleProperty().bind(mutationAction.busyProperty());
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        saveButton.disableProperty().bind(mutationAction.busyProperty());
        scriptController.configure(this::applyScriptDetail);
    }

    void configure(Consumer<OperationResult> changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    void apply(SkillDetail value) {
        detail = Objects.requireNonNull(value, "value");
        nameField.setText(value.name());
        descriptionField.setText(value.description());
        categoryField.setText(value.category());
        tagsField.setText(String.join(", ", value.tags()));
        contentEditor.setText(value.content());
        enabledToggle.setSelected(value.enabled());
        versionLabel.setText("v" + value.version());
        sourceLabel.setText("来源：" + value.source()
                + (value.agentCreated() ? " 🤖" : "")
                + (value.userModified() ? "（已被用户修改）" : ""));
        usageLabel.setText(usageText(value));
        historyCombo.getItems().setAll(value.history());
        historyCombo.getSelectionModel().clearSelection();
        directoryStructureLabel.setText(value.directoryStructure());
        scriptController.apply(value);
        showStatus("", true);
    }

    @FXML
    private void saveRequested() {
        if (detail == null) return;
        UpdateSkillCommand command = new UpdateSkillCommand(
                detail.id(), nameField.getText(), descriptionField.getText(),
                categoryField.getText(), commaSeparated(tagsField.getText()),
                contentEditor.getText(), enabledToggle.isSelected());
        mutationAction.execute(TaskSpec.io("skill-save-" + detail.id()),
                context -> useCases.update(command), this::applyResult, this::showFailure);
    }

    @FXML
    private void rollbackRequested() {
        if (detail == null) return;
        String version = historyCombo.getValue();
        if (version == null || version.isBlank()) {
            showStatus("请先选择历史版本", false);
            return;
        }
        SkillDetail target = detail;
        mutationAction.execute(TaskSpec.io("skill-rollback-" + target.id()),
                context -> dialogs.confirm(new ConfirmRequest(
                        "回滚技能「" + target.name() + "」", "会覆盖当前正文",
                        "确定把技能「" + target.name() + "」回滚到 v" + version
                                + " 吗？当前版本会先归档，可再次回滚。",
                        ConfirmKind.CONFIRM, 60, "", false)).isAllow()
                        ? useCases.rollback(target.id(), version) : null,
                result -> { if (result != null) applyResult(result); }, this::showFailure);
    }

    @FXML
    private void deleteRequested() {
        if (detail == null) return;
        SkillDetail target = detail;
        mutationAction.execute(TaskSpec.io("skill-delete-" + target.id()),
                context -> dialogs.confirm(new ConfirmRequest(
                        "删除技能「" + target.name() + "」", "不可逆",
                        "确定删除技能「" + target.name() + "」吗？将删除整个 "
                                + target.id() + "/ 目录，此操作不可撤销。",
                        ConfirmKind.CONFIRM, 60, "", false)).isAllow()
                        ? useCases.delete(target.id()) : null,
                result -> {
                    if (result == null) return;
                    detail = null;
                    changed.accept(result);
                }, this::showFailure);
    }

    @FXML
    private void openDirectoryRequested() {
        if (detail != null) directoryOpener.open(detail.directory());
    }

    private void applyResult(OperationResult result) {
        if (result.detail() != null) apply(result.detail());
        showStatus(result.message(), true);
        changed.accept(result);
    }

    private void applyScriptDetail(SkillDetail updated) {
        if (detail == null || !detail.id().equals(updated.id())) return;
        detail = updated;
        directoryStructureLabel.setText(updated.directoryStructure());
    }

    private void showFailure(Throwable failure) {
        showStatus(failure.getMessage() == null ? "操作失败" : failure.getMessage(), false);
    }

    private void showStatus(String text, boolean success) {
        statusLabel.setText(text == null ? "" : text);
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        if (!statusLabel.getText().isBlank()) {
            statusLabel.getStyleClass().add(success ? "status-success" : "status-error");
        }
    }

    private static String usageText(SkillDetail value) {
        var usage = value.usage();
        if (usage.routeHits() == 0 && usage.reads() == 0 && usage.samples() == 0) {
            return "尚无使用统计";
        }
        String rate = usage.successRate() < 0 ? "—" : Math.round(usage.successRate() * 100) + "%";
        return "命中 " + usage.routeHits() + " · 读取 " + usage.reads() + " · 成功率 " + rate;
    }

    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,，]")).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        mutationAction.close();
    }
}
