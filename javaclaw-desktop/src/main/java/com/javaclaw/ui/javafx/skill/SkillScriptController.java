package com.javaclaw.ui.javafx.skill;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptDocument;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptMutation;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptReport;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 技能脚本分区 Controller：协调文件用例、确认、结构检查和托管 JShell 运行。 */
public final class SkillScriptController implements AutoCloseable {

    @FXML private TitledPane root;
    @FXML private ComboBox<String> scriptCombo;
    @FXML private TextArea scriptEditor;
    @FXML private TextField scriptArgsField;
    @FXML private TextArea scriptOutputArea;
    @FXML private Label statusLabel;
    @FXML private Button runButton;
    @FXML private Button saveButton;

    private final SkillManagementApplicationService useCases;
    private final DialogService dialogs;
    private final SkillScriptNameDialogFactory nameDialogs;
    private final UiAsyncAction<ScriptDocument> loadAction;
    private final UiAsyncAction<ScriptMutation> mutationAction;
    private final UiAsyncAction<Void> saveAction;
    private final UiAsyncAction<ScriptReport> reportAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<SkillDetail> detailChanged = ignored -> {};
    private String skillId = "";
    private boolean applying;

    public SkillScriptController(
            SkillManagementApplicationService useCases,
            DialogService dialogs,
            SkillScriptNameDialogFactory nameDialogs,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.nameDialogs = Objects.requireNonNull(nameDialogs, "nameDialogs");
        loadAction = new UiAsyncAction<>(tasks, fx);
        mutationAction = new UiAsyncAction<>(tasks, fx);
        saveAction = new UiAsyncAction<>(tasks, fx);
        reportAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        scriptCombo.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applying && selected != null) load(selected);
        });
        runButton.disableProperty().bind(reportAction.busyProperty());
        saveButton.disableProperty().bind(saveAction.busyProperty());
    }

    void configure(Consumer<SkillDetail> detailChanged) {
        this.detailChanged = Objects.requireNonNull(detailChanged, "detailChanged");
    }

    void apply(SkillDetail detail) {
        if (closed.get()) return;
        String previous = skillId.equals(detail.id()) ? scriptCombo.getValue() : null;
        skillId = detail.id();
        applying = true;
        try {
            scriptCombo.getItems().setAll(detail.scripts());
            root.setText("脚本检查与测试（scripts/"
                    + (detail.scripts().isEmpty() ? "，暂无脚本" : "，" + detail.scripts().size() + " 个")
                    + "）");
            if (previous != null && detail.scripts().contains(previous)) {
                scriptCombo.setValue(previous);
            } else if (!detail.scripts().isEmpty()) {
                scriptCombo.getSelectionModel().selectFirst();
            } else {
                scriptCombo.getSelectionModel().clearSelection();
                scriptEditor.clear();
            }
            scriptOutputArea.clear();
            showStatus("", true);
        } finally {
            applying = false;
        }
        if (scriptCombo.getValue() != null) load(scriptCombo.getValue());
    }

    @FXML
    private void createRequested() {
        if (skillId.isBlank()) return;
        nameDialogs.show(root.getScene() == null ? null : root.getScene().getWindow())
                .ifPresent(this::create);
    }

    @FXML
    private void saveRequested() {
        String file = scriptCombo.getValue();
        if (skillId.isBlank() || file == null) {
            showStatus("请先选择脚本", false);
            return;
        }
        saveAction.execute(TaskSpec.io("skill-script-save-" + skillId),
                context -> {
                    useCases.saveScript(skillId, file, scriptEditor.getText());
                    return null;
                }, ignored -> showStatus("已保存", true), this::showFailure);
    }

    @FXML
    private void deleteRequested() {
        String file = scriptCombo.getValue();
        if (skillId.isBlank() || file == null) return;
        mutationAction.execute(TaskSpec.io("skill-script-delete-" + skillId),
                context -> dialogs.confirm(new ConfirmRequest(
                        "删除脚本「" + file + "」", "不可逆",
                        "确定删除脚本「" + file + "」吗？此操作不可撤销。",
                        ConfirmKind.CONFIRM, 60, "", false)).isAllow()
                        ? useCases.deleteScript(skillId, file) : null,
                result -> {
                    if (result == null) return;
                    apply(result.detail());
                    detailChanged.accept(result.detail());
                    showStatus(result.message(), true);
                }, this::showFailure);
    }

    @FXML
    private void checkRequested() {
        reportAction.execute(TaskSpec.cpu("skill-script-check-" + skillId),
                context -> useCases.checkScript(scriptEditor.getText()),
                this::showReport, this::showFailure);
    }

    @FXML
    private void runRequested() {
        if (skillId.isBlank()) return;
        scriptOutputArea.clear();
        showStatus("运行中…", true);
        reportAction.execute(TaskSpec.process("skill-script-run-" + skillId),
                context -> useCases.runScript(
                        skillId, scriptEditor.getText(), scriptArgsField.getText()),
                this::showReport, this::showFailure);
    }

    private void load(String file) {
        String targetSkill = skillId;
        loadAction.execute(TaskSpec.io("skill-script-read-" + targetSkill),
                context -> useCases.readScript(targetSkill, file),
                document -> {
                    if (targetSkill.equals(skillId) && file.equals(scriptCombo.getValue())) {
                        scriptEditor.setText(document.content());
                        showStatus("", true);
                    }
                }, this::showFailure);
    }

    private void create(String fileName) {
        mutationAction.execute(TaskSpec.io("skill-script-create-" + skillId),
                context -> useCases.createScript(skillId, fileName),
                result -> {
                    apply(result.detail());
                    scriptCombo.setValue(result.document().fileName());
                    scriptEditor.setText(result.document().content());
                    detailChanged.accept(result.detail());
                    showStatus(result.message(), true);
                }, this::showFailure);
    }

    private void showReport(ScriptReport report) {
        StringBuilder output = new StringBuilder();
        if (report.timedOut()) {
            output.append("⏱ 执行超时（").append(report.timeoutSeconds()).append("s）已中止\n");
        }
        if (!report.output().isBlank()) output.append("[输出]\n").append(report.output().stripTrailing()).append('\n');
        if (!report.lastValue().isBlank()) output.append("[最后表达式值] ").append(report.lastValue()).append('\n');
        if (!report.problems().isEmpty()) {
            output.append("[诊断]\n");
            report.problems().forEach(problem -> output.append("- ").append(problem).append('\n'));
        }
        if (output.isEmpty()) output.append("（执行完成，无输出）");
        scriptOutputArea.setText(output.toString());
        showStatus(report.success() ? "运行成功" : report.timedOut() ? "超时" : "运行有错误",
                report.success());
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        mutationAction.close();
        saveAction.close();
        reportAction.close();
    }
}
