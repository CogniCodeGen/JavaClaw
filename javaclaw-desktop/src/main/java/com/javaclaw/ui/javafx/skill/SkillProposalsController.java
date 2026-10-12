package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ReviewResult;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 待审技能提案 Controller：加载列表并协调采纳、拒绝动作。 */
public final class SkillProposalsController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private VBox proposalList;
    @FXML private Label emptyLabel;
    @FXML private Label statusLabel;
    @FXML private StackPane loadingOverlay;

    private final SkillManagementApplicationService useCases;
    private final SkillProposalCardFactory cards;
    private final UiAsyncAction<List<ProposalItem>> loadAction;
    private final UiAsyncAction<ReviewResult> reviewAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<ReviewResult> changed = ignored -> {};

    public SkillProposalsController(
            SkillManagementApplicationService useCases,
            SkillProposalCardFactory cards,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cards = Objects.requireNonNull(cards, "cards");
        loadAction = new UiAsyncAction<>(tasks, fx);
        reviewAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        loadingOverlay.visibleProperty().bind(
                loadAction.busyProperty().or(reviewAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
    }

    void configure(Consumer<ReviewResult> changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    void refresh() {
        if (closed.get()) return;
        loadAction.execute(TaskSpec.io("skill-proposals-load"),
                context -> useCases.proposals(), this::render, this::showFailure);
    }

    private void review(String proposalId, boolean approve) {
        reviewAction.execute(TaskSpec.io("skill-proposal-" + (approve ? "approve-" : "reject-") + proposalId),
                context -> approve ? useCases.approveProposal(proposalId)
                        : useCases.rejectProposal(proposalId),
                result -> {
                    render(result.proposals());
                    showStatus(result.message(), true);
                    changed.accept(result);
                }, this::showFailure);
    }

    private void render(List<ProposalItem> proposals) {
        proposalList.getChildren().clear();
        for (ProposalItem proposal : proposals) {
            proposalList.getChildren().add(cards.create(
                    proposal, id -> review(id, true), id -> review(id, false)));
        }
        emptyLabel.setVisible(proposals.isEmpty());
        emptyLabel.setManaged(proposals.isEmpty());
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
        reviewAction.close();
    }
}
