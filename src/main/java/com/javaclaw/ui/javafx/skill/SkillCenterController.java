package com.javaclaw.ui.javafx.skill;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportInspection;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportKind;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.OperationResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ReviewResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.WindowToastController;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 技能中心根 Controller：协调导航、导入流程和子页面状态。 */
public final class SkillCenterController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillCenterController.class);

    @FXML private StackPane root;
    @FXML private ListView<SkillSummary> skillList;
    @FXML private Label pathLabel;
    @FXML private Button proposalsButton;
    @FXML private Node emptyPanel;
    @FXML private Node editor;
    @FXML private SkillEditorController editorController;
    @FXML private Node proposals;
    @FXML private SkillProposalsController proposalsController;
    @FXML private Node bundles;
    @FXML private SkillBundlesController bundlesController;
    @FXML private StackPane loadingOverlay;
    @FXML private WindowToastController toastController;

    private final SkillManagementApplicationService useCases;
    private final DialogService dialogs;
    private final ExternalDirectoryOpener directoryOpener;
    private final FxDispatcher fx;
    private final SkillListCellFactory cells;
    private final SkillCenterViewModel viewModel = new SkillCenterViewModel();
    private final UiAsyncAction<Snapshot> snapshotAction;
    private final UiAsyncAction<SkillDetail> detailAction;
    private final UiAsyncAction<OperationResult> createAction;
    private final UiAsyncAction<String> choiceAction;
    private final UiAsyncAction<ImportInspection> inspectionAction;
    private final UiAsyncAction<ImportResult> importAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable closeAction = () -> {};
    private AutoCloseable proposalSubscription;
    private boolean applyingSelection;

    public SkillCenterController(
            SkillManagementApplicationService useCases,
            DialogService dialogs,
            ExternalDirectoryOpener directoryOpener,
            FxDispatcher fx,
            SkillListCellFactory cells,
            @Qualifier("workspaceTaskScope") TaskScope tasks) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.directoryOpener = Objects.requireNonNull(directoryOpener, "directoryOpener");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.cells = Objects.requireNonNull(cells, "cells");
        snapshotAction = new UiAsyncAction<>(tasks, fx);
        detailAction = new UiAsyncAction<>(tasks, fx);
        createAction = new UiAsyncAction<>(tasks, fx);
        choiceAction = new UiAsyncAction<>(tasks, fx);
        inspectionAction = new UiAsyncAction<>(tasks, fx);
        importAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        skillList.setCellFactory(ignored -> cells.create());
        skillList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> {
                    if (!applyingSelection && selected != null) selectSkill(selected.id());
                });
        viewModel.panelProperty().addListener((ignored, previous, panel) -> showPanel(panel));
        editorController.configure(this::editorChanged);
        proposalsController.configure(this::proposalsChanged);
        loadingOverlay.visibleProperty().bind(Bindings.or(
                snapshotAction.busyProperty(), Bindings.or(detailAction.busyProperty(),
                Bindings.or(createAction.busyProperty(), Bindings.or(choiceAction.busyProperty(),
                Bindings.or(inspectionAction.busyProperty(), importAction.busyProperty()))))));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        proposalSubscription = useCases.subscribeToProposalChanges(this::proposalChanged);
        showPanel(SkillCenterViewModel.Panel.EMPTY);
    }

    void configure(Runnable closeAction) {
        this.closeAction = closeAction == null ? () -> {} : closeAction;
    }

    void prepare() {
        requestSnapshot();
    }

    @FXML private void closeRequested() { closeAction.run(); }

    @FXML
    private void createRequested() {
        createAction.execute(TaskSpec.io("skill-create"), context -> useCases.create(),
                this::editorChanged, this::showFailure);
    }

    @FXML
    private void proposalsRequested() {
        clearSkillSelection();
        viewModel.show(SkillCenterViewModel.Panel.PROPOSALS);
        proposalsController.refresh();
    }

    @FXML
    private void bundlesRequested() {
        clearSkillSelection();
        viewModel.show(SkillCenterViewModel.Panel.BUNDLES);
        bundlesController.refresh();
    }

    @FXML
    private void importRequested() {
        choiceAction.execute(TaskSpec.io("skill-import-source-choice"),
                context -> dialogs.choose(new ChoiceRequest(
                        "导入技能", "选择导入来源",
                        List.of(new ChoiceOption("directory", "目录", "导入包含 SKILL.md 的文件夹"),
                                new ChoiceOption("zip", "Zip", "导入 .zip 技能包")), 60)),
                this::chooseImportSource, this::showFailure);
    }

    @FXML
    private void openSkillsDirectoryRequested() {
        Snapshot snapshot = viewModel.snapshotProperty().get();
        if (snapshot != null) directoryOpener.open(snapshot.skillsDirectory());
    }

    private void requestSnapshot() {
        if (closed.get()) return;
        snapshotAction.execute(TaskSpec.io("skill-center-snapshot"),
                context -> useCases.snapshot(), this::applySnapshot, this::showFailure);
    }

    private void selectSkill(String skillId) {
        viewModel.selectSkill(skillId);
        detailAction.execute(TaskSpec.io("skill-detail-" + skillId),
                context -> useCases.detail(skillId),
                detail -> {
                    if (skillId.equals(viewModel.selectedSkillIdProperty().get())) {
                        editorController.apply(detail);
                    }
                }, this::showFailure);
    }

    private void editorChanged(OperationResult result) {
        applySnapshot(result.snapshot());
        if (result.detail() == null) {
            clearSkillSelection();
            viewModel.show(SkillCenterViewModel.Panel.EMPTY);
        } else {
            selectInList(result.detail().id());
            viewModel.selectSkill(result.detail().id());
            editorController.apply(result.detail());
        }
        toastController.show(result.message());
    }

    private void proposalsChanged(ReviewResult result) {
        applySnapshot(result.snapshot());
        viewModel.show(SkillCenterViewModel.Panel.PROPOSALS);
        toastController.show(result.message());
    }

    private void applySnapshot(Snapshot snapshot) {
        viewModel.apply(snapshot);
        pathLabel.setText(snapshot.skillsDirectory().toString());
        proposalsButton.setText(snapshot.pendingProposalCount() > 0
                ? "待审提案 (" + snapshot.pendingProposalCount() + ")" : "待审提案");
        String selected = viewModel.selectedSkillIdProperty().get();
        applyingSelection = true;
        try {
            skillList.getItems().setAll(snapshot.skills());
            if (selected == null || selected.isBlank()) skillList.getSelectionModel().clearSelection();
            else selectInList(selected);
        } finally {
            applyingSelection = false;
        }
    }

    private void showPanel(SkillCenterViewModel.Panel panel) {
        visible(emptyPanel, panel == SkillCenterViewModel.Panel.EMPTY);
        visible(editor, panel == SkillCenterViewModel.Panel.EDITOR);
        visible(proposals, panel == SkillCenterViewModel.Panel.PROPOSALS);
        visible(bundles, panel == SkillCenterViewModel.Panel.BUNDLES);
    }

    private void chooseImportSource(String choice) {
        if (choice == null || choice.isBlank()) return;
        if ("directory".equals(choice)) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择包含 SKILL.md 的目录");
            var selected = chooser.showDialog(root.getScene().getWindow());
            if (selected != null) inspectImport(selected.toPath(), ImportKind.DIRECTORY);
        } else if ("zip".equals(choice)) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择技能 Zip 文件");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip (*.zip)", "*.zip"));
            var selected = chooser.showOpenDialog(root.getScene().getWindow());
            if (selected != null) inspectImport(selected.toPath(), ImportKind.ZIP);
        }
    }

    private void inspectImport(Path source, ImportKind kind) {
        inspectionAction.execute(TaskSpec.io("skill-import-inspect"),
                context -> useCases.inspectImport(source, kind),
                inspection -> importSkill(source, kind, inspection), this::showFailure);
    }

    private void importSkill(Path source, ImportKind kind, ImportInspection inspection) {
        importAction.execute(TaskSpec.io("skill-import"), context -> {
            if (inspection.containsScripts()) {
                String scriptList = String.join("\n", inspection.scripts());
                boolean confirmed = dialogs.confirm(new ConfirmRequest(
                        "导入包含脚本的技能", "可执行代码",
                        "检测到 scripts/ 目录下的脚本文件：\n\n" + scriptList
                                + "\n\n这些脚本可能被执行。请确认你信任该技能来源。",
                        ConfirmKind.CONFIRM, 60, "", false)).isAllow();
                if (!confirmed) return null;
            }
            return useCases.importSkill(source, kind);
        }, result -> {
            if (result == null) return;
            applySnapshot(result.snapshot());
            toastController.show(result.message() + (result.installedDirectory() == null ? ""
                    : "：" + result.installedDirectory()));
        }, this::showFailure);
    }

    private void proposalChanged() {
        fx.dispatch(() -> {
            if (closed.get()) return;
            requestSnapshot();
            if (viewModel.panelProperty().get() == SkillCenterViewModel.Panel.PROPOSALS) {
                proposalsController.refresh();
            }
        });
    }

    private void selectInList(String id) {
        skillList.getItems().stream().filter(item -> item.id().equals(id))
                .findFirst().ifPresent(skillList.getSelectionModel()::select);
    }

    private void clearSkillSelection() {
        applyingSelection = true;
        try { skillList.getSelectionModel().clearSelection(); }
        finally { applyingSelection = false; }
    }

    private void showFailure(Throwable failure) {
        toastController.show(failure.getMessage() == null ? "操作失败" : failure.getMessage());
    }

    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(proposalSubscription);
        snapshotAction.close();
        detailAction.close();
        createAction.close();
        choiceAction.close();
        inspectionAction.close();
        importAction.close();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception failure) {
            log.debug("关闭技能提案订阅失败", failure);
        }
    }
}
