package com.javaclaw.ui.javafx.memory;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.EditFactCommand;
import com.javaclaw.application.memory.MemoryApplicationService.FactItem;
import com.javaclaw.application.memory.MemoryApplicationService.OperationResult;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** 事实列表、批量选择和编辑动作的分区 Controller。 */
public final class MemoryFactsController
        implements MemoryHostedSection, MemoryFactActions, AutoCloseable {
    @FXML private Button batchButton;
    @FXML private Button addButton;
    @FXML private HBox batchBar;
    @FXML private Label selectedCount;
    @FXML private Button deleteSelectedButton;
    @FXML private VBox groups;
    @FXML private Label empty;

    private final MemoryApplicationService useCases;
    private final DialogService dialogs;
    private final MemoryFactDialogFactory factDialog;
    private final MemoryComponentFactory components;
    private final UiAsyncAction<OperationResult> action;
    private final List<MemoryChildView<?>> children = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Boolean> expandedGroups = new HashMap<>();
    private MemorySectionHost host;
    private Snapshot snapshot;
    private String query = "";
    private boolean batchMode;

    public MemoryFactsController(
            MemoryApplicationService useCases,
            DialogService dialogs,
            MemoryFactDialogFactory factDialog,
            MemoryComponentFactory components,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.factDialog = Objects.requireNonNull(factDialog, "factDialog");
        this.components = Objects.requireNonNull(components, "components");
        action = new UiAsyncAction<>(tasks, fx);
    }

    @Override public void configure(MemorySectionHost host) { this.host = host; }

    @Override
    public void apply(Snapshot snapshot, String query) {
        this.snapshot = snapshot;
        this.query = query == null ? "" : query;
        selected.removeIf(id -> snapshot.facts().stream().noneMatch(item -> item.id().equals(id)));
        expandedGroups.keySet().removeIf(section -> snapshot.facts().stream()
                .noneMatch(item -> item.section().equals(section)));
        render();
    }

    @FXML
    private void toggleBatchMode() {
        batchMode = !batchMode;
        selected.clear();
        render();
    }

    @FXML
    private void finishBatchMode() {
        batchMode = false;
        selected.clear();
        render();
    }

    @FXML
    private void addFact() {
        if (snapshot == null) return;
        List<String> sections = snapshot.facts().stream().map(FactItem::section)
                .distinct().sorted().toList();
        factDialog.show(host.window(), sections).ifPresent(command ->
                execute("memory-add-fact", () -> useCases.addFact(command)));
    }

    @FXML
    private void deleteSelected() {
        if (selected.isEmpty()) return;
        List<String> ids = List.copyOf(selected);
        action.execute(TaskSpec.io("memory-delete-facts"), context -> {
            var decision = dialogs.confirm(new ConfirmRequest(
                    "删除事实", "批量删除",
                    "删除所选 " + ids.size() + " 条事实？此操作不可撤销。",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.deleteFacts(ids) : null;
        }, result -> {
            if (result == null) return;
            selected.clear();
            host.apply(result);
        }, failure -> host.showMessage("删除事实失败：" + failure.getMessage()));
    }

    @Override
    public void toggleSelected(String id) {
        if (!selected.add(id)) selected.remove(id);
        render();
    }

    @Override
    public void edit(String id, String text) {
        execute("memory-edit-fact", () -> useCases.editFact(new EditFactCommand(id, text)));
    }

    @Override
    public void togglePin(String id) {
        execute("memory-pin-fact", () -> useCases.toggleFactPin(id));
    }

    @Override
    public void restore(String id) {
        execute("memory-restore-fact", () -> useCases.restoreFact(id));
    }

    @Override
    public void delete(String id, String text) {
        action.execute(TaskSpec.io("memory-delete-fact"), context -> {
            var decision = dialogs.confirm(new ConfirmRequest(
                    "删除事实", "确认删除", "删除事实：" + text + " ?",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.deleteFacts(List.of(id)) : null;
        }, result -> {
            if (result != null) host.apply(result);
        }, failure -> host.showMessage("删除事实失败：" + failure.getMessage()));
    }

    private void render() {
        if (snapshot == null) return;
        closeChildren();
        selectedCount.setText("已选 " + selected.size() + " 条");
        batchBar.setVisible(batchMode);
        batchBar.setManaged(batchMode);
        deleteSelectedButton.setDisable(selected.isEmpty());
        batchButton.setText(batchMode ? "退出批量" : "批量选择");
        Map<String, List<FactItem>> bySection = new TreeMap<>();
        snapshot.facts().stream()
                .filter(item -> MemoryUiText.matches(query, item.text(), item.section()))
                .forEach(item -> bySection.computeIfAbsent(
                        item.section(), ignored -> new ArrayList<>()).add(item));
        empty.setText(query.isBlank() ? "尚无事实，对话后会自动蒸馏沉淀" : "没有匹配的事实");
        empty.setVisible(bySection.isEmpty());
        empty.setManaged(bySection.isEmpty());
        bySection.forEach((section, facts) -> {
            MemoryChildView<VBox> child = components.factGroup(
                    section, facts, batchMode, selected, this,
                    expandedGroups.getOrDefault(section, true),
                    expanded -> expandedGroups.put(section, expanded));
            children.add(child);
            groups.getChildren().add(child.root());
        });
    }

    private void execute(String name, Callable<OperationResult> operation) {
        action.execute(TaskSpec.io(name), context -> operation.call(), host::apply,
                failure -> host.showMessage("事实操作失败：" + failure.getMessage()));
    }

    private void closeChildren() {
        groups.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedGroupCount() { return children.size(); }

    @Override
    public void close() {
        action.close();
        closeChildren();
    }
}
