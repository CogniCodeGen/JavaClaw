package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.ImportResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Coordinates document filtering, imports, retrieval toggles and detail actions. */
public final class KnowledgeDocumentsController implements AutoCloseable {
    @FXML private VBox root;
    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private TextField filterField;
    @FXML private Button importButton;
    @FXML private VBox importPopover;
    @FXML private ListView<Document> documentList;
    @FXML private VBox detailDrawer;
    @FXML private Label detailTypeLabel;
    @FXML private Label detailScopeLabel;
    @FXML private Label detailNameLabel;
    @FXML private Label detailChunksLabel;
    @FXML private Label detailEnabledLabel;
    @FXML private Label detailTimeLabel;
    @FXML private ListView<String> previewList;
    @FXML private ToggleSwitch detailToggle;
    @FXML private StackPane loadingOverlay;

    private final KnowledgeApplicationService useCases;
    private final KnowledgeDocumentCellFactory documentCells;
    private final KnowledgePreviewCellFactory previewCells;
    private final KnowledgeImportPicker picker;
    private final KnowledgeTextImportDialogFactory textImports;
    private final DialogService dialogs;
    private final KnowledgeDocumentsViewModel viewModel = new KnowledgeDocumentsViewModel();
    private final UiAsyncAction<Snapshot> mutationAction;
    private final UiAsyncAction<ImportResult> importAction;
    private Consumer<Snapshot> snapshotConsumer = ignored -> { };
    private Consumer<String> notifier = ignored -> { };
    private boolean updatingToggle;

    public KnowledgeDocumentsController(
            KnowledgeApplicationService useCases,
            KnowledgeDocumentCellFactory documentCells,
            KnowledgePreviewCellFactory previewCells,
            KnowledgeImportPicker picker,
            KnowledgeTextImportDialogFactory textImports,
            DialogService dialogs,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.documentCells = Objects.requireNonNull(documentCells, "documentCells");
        this.previewCells = Objects.requireNonNull(previewCells, "previewCells");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.textImports = Objects.requireNonNull(textImports, "textImports");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        mutationAction = new UiAsyncAction<>(tasks, fx);
        importAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        documentList.setItems(viewModel.documents());
        documentList.setCellFactory(ignored -> documentCells.create(this::select, this::toggle));
        previewList.setCellFactory(ignored -> previewCells.create());
        filterField.textProperty().bindBidirectional(viewModel.filterProperty());
        detailDrawer.visibleProperty().bind(viewModel.selectedProperty().isNotNull());
        detailDrawer.managedProperty().bind(detailDrawer.visibleProperty());
        loadingOverlay.visibleProperty().bind(
                mutationAction.busyProperty().or(importAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        detailToggle.selectedProperty().addListener((ignored, previous, enabled) -> {
            if (!updatingToggle && viewModel.selected() != null) {
                toggle(viewModel.selected(), enabled);
            }
        });
        showImportPopover(false);
    }

    void configure(Consumer<Snapshot> snapshots, Consumer<String> messages) {
        snapshotConsumer = snapshots == null ? ignored -> { } : snapshots;
        notifier = messages == null ? ignored -> { } : messages;
    }

    void apply(Snapshot snapshot) {
        viewModel.apply(snapshot);
        updateScopeText();
        documentList.getSelectionModel().select(viewModel.selected());
        showDetails(viewModel.selected());
    }

    void setScope(Scope scope) {
        viewModel.setScope(scope);
        updateScopeText();
        documentList.getSelectionModel().clearSelection();
        showDetails(viewModel.selected());
    }

    @FXML private void importMenuRequested() { showImportPopover(!importPopover.isVisible()); }
    @FXML private void importFilesRequested() {
        showImportPopover(false);
        List<Path> files = picker.chooseFiles(root.getScene().getWindow());
        if (!files.isEmpty()) importFiles(files);
    }
    @FXML private void importDirectoryRequested() {
        showImportPopover(false);
        picker.chooseDirectory(root.getScene().getWindow()).ifPresent(this::importDirectory);
    }
    @FXML private void importTextRequested() {
        showImportPopover(false);
        textImports.show(root.getScene().getWindow()).ifPresent(draft -> importText(draft));
    }
    @FXML private void enableAllRequested() { setAllEnabled(true); }
    @FXML private void disableAllRequested() { setAllEnabled(false); }
    @FXML private void closeDetailsRequested() { clearSelection(); }
    @FXML private void deleteRequested() { deleteSelected(); }

    private void select(Document document) {
        if (document != null && document.equals(viewModel.selected())) {
            clearSelection();
            return;
        }
        viewModel.select(document);
        documentList.getSelectionModel().select(document);
        showDetails(document);
    }

    private void clearSelection() {
        viewModel.clearSelection();
        documentList.getSelectionModel().clearSelection();
        showDetails(null);
    }

    private void showDetails(Document document) {
        if (document == null) {
            previewList.setItems(FXCollections.emptyObservableList());
            return;
        }
        String extension = extension(document.name()).toUpperCase(Locale.ROOT);
        detailTypeLabel.setText(extension);
        detailNameLabel.setText(document.name());
        detailScopeLabel.setText(document.scope() == Scope.GLOBAL ? "全局" : "工作区");
        detailScopeLabel.getStyleClass().removeAll("kc-scope-global", "kc-scope-workspace");
        detailScopeLabel.getStyleClass().add(document.scope() == Scope.GLOBAL
                ? "kc-scope-global" : "kc-scope-workspace");
        detailChunksLabel.setText(Integer.toString(document.chunkCount()));
        detailEnabledLabel.setText(document.enabled() ? "已启用" : "已停用");
        detailTimeLabel.setText(document.importedAt().isBlank() ? "—" : document.importedAt());
        previewList.setItems(FXCollections.observableArrayList(document.previews()));
        updatingToggle = true;
        detailToggle.setSelected(document.enabled());
        updatingToggle = false;
    }

    private void toggle(Document document, boolean enabled) {
        mutationAction.execute(TaskSpec.io("knowledge-toggle-document"),
                context -> useCases.setDocumentEnabled(document.name(), enabled),
                snapshot -> mutationSucceeded(snapshot,
                        enabled ? "已启用文档检索" : "已停用文档检索"),
                failure -> failed("更新文档检索状态失败", failure));
    }

    private void setAllEnabled(boolean enabled) {
        mutationAction.execute(TaskSpec.io("knowledge-toggle-all"),
                context -> useCases.setAllEnabled(enabled, viewModel.scope()),
                snapshot -> mutationSucceeded(snapshot,
                        enabled ? "当前范围的文档已全部启用" : "当前范围的文档已全部停用"),
                failure -> failed("批量更新失败", failure));
    }

    private void deleteSelected() {
        Document selected = viewModel.selected();
        if (selected == null) return;
        mutationAction.execute(TaskSpec.io("knowledge-delete-document"), context -> {
            boolean allowed = dialogs.confirm(new ConfirmRequest(
                    "knowledge_delete", "不可逆",
                    "确定要删除文档「" + selected.name() + "」吗？\n该文档包含 "
                            + selected.chunkCount() + " 个片段，删除后不可撤销。",
                    ConfirmKind.CONFIRM, 0, "", false)).isAllow();
            return allowed ? useCases.deleteDocument(selected.name()) : null;
        }, snapshot -> {
            if (snapshot == null) return;
            clearSelection();
            mutationSucceeded(snapshot, "已删除文档：" + selected.name());
        }, failure -> failed("删除文档失败", failure));
    }

    private void importFiles(List<Path> files) {
        Scope scope = importScope();
        importAction.execute(TaskSpec.io("knowledge-import-files"),
                context -> useCases.importFiles(files, scope),
                this::importSucceeded, failure -> failed("导入文件失败", failure));
    }

    private void importDirectory(Path directory) {
        Scope scope = importScope();
        importAction.execute(TaskSpec.io("knowledge-import-directory"), context -> {
            List<Path> files;
            try (Stream<Path> entries = Files.list(directory)) {
                files = entries.filter(Files::isRegularFile)
                        .filter(KnowledgeDocumentsController::supported).sorted().toList();
            }
            if (files.isEmpty()) {
                throw new ValidationException("目录中没有支持的文档（PDF / TXT / Markdown 等）");
            }
            return useCases.importFiles(files, scope);
        }, this::importSucceeded, failure -> failed("导入目录失败", failure));
    }

    private void importText(KnowledgeTextImportDialogFactory.Draft draft) {
        Scope scope = importScope();
        importAction.execute(TaskSpec.io("knowledge-import-text"),
                context -> useCases.importText(draft.title(), draft.text(), scope),
                this::importSucceeded, failure -> failed("导入文本失败", failure));
    }

    private void importSucceeded(ImportResult result) {
        snapshotConsumer.accept(result.snapshot());
        String message = result.failed() == 0
                ? "全部导入成功（" + result.succeeded() + " 个文档）"
                : "导入完成：" + result.succeeded() + " 成功，" + result.failed() + " 失败";
        if (!result.failures().isEmpty()) message += "；" + result.failures().getFirst();
        notifier.accept(message);
    }

    private void mutationSucceeded(Snapshot snapshot, String message) {
        snapshotConsumer.accept(snapshot);
        notifier.accept(message);
    }

    private void failed(String operation, Throwable failure) {
        notifier.accept(operation + "：" + errorMessage(failure));
    }

    private void updateScopeText() {
        Scope scope = viewModel.scope();
        titleLabel.setText(switch (scope) {
            case ALL -> "全部文档";
            case WORKSPACE -> "工作区知识库";
            case GLOBAL -> "全局知识库";
        });
        subtitleLabel.setText("共 " + viewModel.documents().size() + " 个文档");
    }

    private Scope importScope() {
        return viewModel.scope() == Scope.GLOBAL ? Scope.GLOBAL : Scope.WORKSPACE;
    }

    private void showImportPopover(boolean show) {
        importPopover.setVisible(show);
        importPopover.setManaged(show);
        importButton.setText(show ? "收起导入" : "＋ 导入");
    }

    private static boolean supported(Path file) {
        String value = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return value.endsWith(".pdf") || value.endsWith(".txt") || value.endsWith(".text")
                || value.endsWith(".md") || value.endsWith(".markdown")
                || value.endsWith(".log") || value.endsWith(".csv")
                || value.endsWith(".json") || value.endsWith(".xml")
                || value.endsWith(".html") || value.endsWith(".htm");
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "TXT" : name.substring(index + 1);
    }

    private static String errorMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        mutationAction.close();
        importAction.close();
        filterField.textProperty().unbindBidirectional(viewModel.filterProperty());
        detailDrawer.visibleProperty().unbind();
        detailDrawer.managedProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        documentList.setCellFactory(null);
        documentList.setItems(null);
        previewList.setCellFactory(null);
        previewList.setItems(null);
        snapshotConsumer = ignored -> { };
        notifier = ignored -> { };
    }
}
