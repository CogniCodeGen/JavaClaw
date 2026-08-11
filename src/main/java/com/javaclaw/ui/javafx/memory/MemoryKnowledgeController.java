package com.javaclaw.ui.javafx.memory;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.KnowledgeDocument;
import com.javaclaw.application.memory.MemoryApplicationService.OperationResult;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 知识文档分区，所有写操作经 Memory UseCase。 */
public final class MemoryKnowledgeController
        implements MemoryHostedSection, AutoCloseable {
    @FXML private VBox table;
    @FXML private VBox rows;
    @FXML private VBox emptyPanel;
    @FXML private Label emptyText;

    private final MemoryApplicationService useCases;
    private final DialogService dialogs;
    private final MemoryComponentFactory components;
    private final UiAsyncAction<OperationResult> action;
    private final List<MemoryChildView<?>> children = new ArrayList<>();
    private MemorySectionHost host;

    public MemoryKnowledgeController(
            MemoryApplicationService useCases,
            DialogService dialogs,
            MemoryComponentFactory components,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.components = Objects.requireNonNull(components, "components");
        action = new UiAsyncAction<>(tasks, fx);
    }

    @Override public void configure(MemorySectionHost host) { this.host = host; }

    @Override
    public void apply(Snapshot snapshot, String query) {
        closeChildren();
        List<KnowledgeDocument> matches = snapshot.documents().stream()
                .filter(item -> MemoryUiText.matches(query, item.name())).toList();
        boolean empty = matches.isEmpty();
        table.setVisible(!empty);
        table.setManaged(!empty);
        emptyPanel.setVisible(empty);
        emptyPanel.setManaged(empty);
        emptyText.setText(query == null || query.isBlank()
                ? "支持 .md · .pdf · .txt · .docx" : "没有匹配的文档");
        for (KnowledgeDocument document : matches) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.knowledgeDocument(
                    document, this::reindex, this::delete);
            children.add(child);
            rows.getChildren().add(child.root());
        }
    }

    private void reindex(String document) {
        execute("memory-reindex-document", () -> useCases.reindexDocument(document));
        host.showMessage("正在后台重建「" + document + "」索引…");
    }

    private void delete(String document) {
        action.execute(TaskSpec.io("memory-delete-document"), context -> {
            var decision = dialogs.confirm(new ConfirmRequest(
                    "删除知识文档", "确认删除",
                    "删除文档「" + document + "」及其全部分块？",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.deleteDocument(document) : null;
        }, result -> {
            if (result != null) host.apply(result);
        }, failure -> host.showMessage("删除文档失败：" + failure.getMessage()));
    }

    private void execute(String name, java.util.concurrent.Callable<OperationResult> operation) {
        action.execute(TaskSpec.io(name), context -> operation.call(), host::apply,
                failure -> host.showMessage("操作失败：" + failure.getMessage()));
    }

    private void closeChildren() {
        rows.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedDocumentCount() { return children.size(); }

    @Override
    public void close() {
        action.close();
        closeChildren();
    }
}
