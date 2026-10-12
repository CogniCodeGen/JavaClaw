package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Objects;
import java.util.function.Consumer;

/** Coordinates an isolated retrieval test without entering the conversation pipeline. */
public final class KnowledgeSearchController implements AutoCloseable {
    @FXML private VBox root;
    @FXML private TextField queryField;
    @FXML private Button searchButton;
    @FXML private Label modelLabel;
    @FXML private Label documentCountLabel;
    @FXML private Label summaryLabel;
    @FXML private ListView<com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit>
            resultList;
    @FXML private StackPane loadingOverlay;

    private final KnowledgeApplicationService useCases;
    private final KnowledgeSearchHitCellFactory cells;
    private final KnowledgeSearchViewModel viewModel = new KnowledgeSearchViewModel();
    private final UiAsyncAction<SearchResult> searchAction;
    private Consumer<String> notifier = ignored -> { };

    public KnowledgeSearchController(
            KnowledgeApplicationService useCases,
            KnowledgeSearchHitCellFactory cells,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cells = Objects.requireNonNull(cells, "cells");
        searchAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        resultList.setItems(viewModel.hits());
        resultList.setCellFactory(ignored -> cells.create(viewModel::query));
        summaryLabel.textProperty().bind(viewModel.summaryProperty());
        searchButton.disableProperty().bind(searchAction.busyProperty());
        loadingOverlay.visibleProperty().bind(searchAction.busyProperty());
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
    }

    void configure(Consumer<String> messages) {
        notifier = messages == null ? ignored -> { } : messages;
    }

    void apply(Snapshot snapshot) {
        modelLabel.setText(snapshot.settings().model().isBlank()
                ? "未配置嵌入模型" : snapshot.settings().model());
        documentCountLabel.setText(snapshot.enabledCount() + " 个文档参与检索");
    }

    @FXML
    private void searchRequested() {
        String query = queryField.getText();
        searchAction.execute(TaskSpec.io("knowledge-search-test"),
                context -> useCases.search(query), viewModel::apply,
                failure -> {
                    String message = errorMessage(failure);
                    viewModel.failure("检索失败：" + message);
                    notifier.accept("检索失败：" + message);
                });
    }

    @Override
    public void close() {
        searchAction.close();
        summaryLabel.textProperty().unbind();
        searchButton.disableProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        resultList.setCellFactory(null);
        resultList.setItems(null);
        notifier = ignored -> { };
    }

    private static String errorMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
