package com.javaclaw.ui.javafx.memory;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.CorrectionItem;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/** 用户纠错审计和维护分区。 */
public final class MemoryCorrectionsController
        implements MemoryHostedSection, AutoCloseable {
    @FXML private VBox rows;
    @FXML private Label empty;
    private final MemoryApplicationService useCases;
    private final DialogService dialogs;
    private final MemoryComponentFactory components;
    private final UiAsyncAction<OperationResult> action;
    private final List<MemoryChildView<?>> children = new ArrayList<>();
    private MemorySectionHost host;

    public MemoryCorrectionsController(
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
        List<CorrectionItem> matches = snapshot.corrections().stream()
                .filter(item -> MemoryUiText.matches(query,
                        item.wrongClaim(), item.correctClaim(), item.sourceInput()))
                .sorted(Comparator.comparingLong(CorrectionItem::timestamp).reversed()).toList();
        empty.setText(query == null || query.isBlank()
                ? "暂无纠错记录（只有同时说明错的内容和正确内容才会记录）"
                : "没有匹配的纠错记录");
        empty.setVisible(matches.isEmpty());
        empty.setManaged(matches.isEmpty());
        for (CorrectionItem item : matches) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.correction(
                    item, this::revoke, this::delete);
            children.add(child);
            rows.getChildren().add(child.root());
        }
    }

    private void revoke(String id) {
        execute("memory-revoke-correction", () -> useCases.revokeCorrection(id));
    }

    private void delete(String id) {
        action.execute(TaskSpec.io("memory-delete-correction"), context -> {
            var decision = dialogs.confirm(new ConfirmRequest(
                    "删除纠错记录", "确认删除",
                    "删除这条纠错记录？被它标记的事实不会自动恢复。",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.deleteCorrection(id) : null;
        }, result -> {
            if (result != null) host.apply(result);
        }, failure -> host.showMessage("删除纠错失败：" + failure.getMessage()));
    }

    private void execute(String name, Callable<OperationResult> operation) {
        action.execute(TaskSpec.io(name), context -> operation.call(), host::apply,
                failure -> host.showMessage("纠错操作失败：" + failure.getMessage()));
    }

    private void closeChildren() {
        rows.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedCorrectionCount() { return children.size(); }

    @Override
    public void close() {
        action.close();
        closeChildren();
    }
}
