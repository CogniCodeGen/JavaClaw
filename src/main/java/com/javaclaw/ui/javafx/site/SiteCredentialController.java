package com.javaclaw.ui.javafx.site;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.site.SiteCredentialApplicationService;
import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import com.javaclaw.application.site.SiteCredentialApplicationService.SaveCommand;
import com.javaclaw.application.site.SiteCredentialApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.site.SiteCredentialCardFactory.Card;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 站点管理 Controller：协调 FXML 事件、应用用例和卡片生命周期。 */
public final class SiteCredentialController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private Button addButton;
    @FXML private VBox credentialList;
    @FXML private Label emptyLabel;
    @FXML private Label storageLabel;
    @FXML private Label statusLabel;
    @FXML private StackPane loadingOverlay;

    private final SiteCredentialApplicationService useCases;
    private final DialogService dialogs;
    private final SiteCredentialCardFactory cards;
    private final SiteCredentialEditorFactory editors;
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<Snapshot> mutationAction;
    private final SiteCredentialViewModel viewModel = new SiteCredentialViewModel();
    private final List<Card> cardViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SiteCredentialController(
            SiteCredentialApplicationService useCases,
            DialogService dialogs,
            SiteCredentialCardFactory cards,
            SiteCredentialEditorFactory editors,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.cards = Objects.requireNonNull(cards, "cards");
        this.editors = Objects.requireNonNull(editors, "editors");
        loadAction = new UiAsyncAction<>(tasks, fx);
        mutationAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        viewModel.loadingProperty().bind(loadAction.busyProperty());
        viewModel.mutatingProperty().bind(mutationAction.busyProperty());
        loadingOverlay.visibleProperty().bind(Bindings.or(
                viewModel.loadingProperty(), viewModel.mutatingProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        addButton.disableProperty().bind(viewModel.mutatingProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
    }

    /** 面板可见后再读取凭据目录。 */
    public void activate() {
        if (closed.get()) return;
        requestSnapshot();
    }

    public void deactivate() { loadAction.cancel(); }

    @FXML
    private void addRequested() {
        editors.show(root.getScene() == null ? null : root.getScene().getWindow(), null)
                .ifPresent(this::save);
    }

    private void editRequested(String id) {
        Credential credential;
        try {
            credential = viewModel.snapshotProperty().get().require(id);
        } catch (RuntimeException failure) {
            showFailure("无法编辑站点", failure);
            return;
        }
        editors.show(root.getScene() == null ? null : root.getScene().getWindow(), credential)
                .ifPresent(this::save);
    }

    private void save(SaveCommand command) {
        boolean creating = command.id().isBlank();
        mutate("site-credential-save", () -> useCases.save(command),
                creating ? "已添加站点" : "已更新站点");
    }

    private void resetRequested(String id) {
        Credential credential = viewModel.snapshotProperty().get().require(id);
        mutate("site-session-reset-" + id, () -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "重置会话", "重置站点会话",
                    "确认清除「" + credential.name() + "」的已保存会话？\n"
                            + "下次访问该站点需要重新登录。",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.clearSession(id) : null;
        }, "已重置 " + credential.name() + " 的会话");
    }

    private void deleteRequested(String id) {
        Credential credential = viewModel.snapshotProperty().get().require(id);
        mutate("site-credential-delete-" + id, () -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "删除站点", "删除站点凭据",
                    "确认删除站点「" + credential.name() + "」？\n会同时删除其已保存会话。",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.delete(id) : null;
        }, "已删除 " + credential.name());
    }

    private void requestSnapshot() {
        loadAction.execute(TaskSpec.io("site-credential-list"),
                context -> useCases.snapshot(), this::apply,
                failure -> showFailure("加载站点凭据失败", failure));
    }

    private void mutate(String taskName, java.util.concurrent.Callable<Snapshot> operation,
                        String successMessage) {
        mutationAction.execute(TaskSpec.io(taskName), context -> operation.call(), snapshot -> {
            if (snapshot == null) return;
            apply(snapshot);
            viewModel.showStatus(successMessage, false);
            applyStatusStyle(false);
        }, failure -> showFailure("站点凭据操作失败", failure));
    }

    private void apply(Snapshot snapshot) {
        if (closed.get()) return;
        viewModel.apply(snapshot);
        storageLabel.setText("配置存储: " + snapshot.storageDescription());
        renderCards(snapshot.credentials());
    }

    private void renderCards(List<Credential> credentials) {
        closeCards();
        for (Credential credential : credentials) {
            Card card = cards.create(credential, this::editRequested,
                    this::resetRequested, this::deleteRequested);
            cardViews.add(card);
            credentialList.getChildren().add(card.root());
        }
        emptyLabel.setVisible(credentials.isEmpty());
        emptyLabel.setManaged(credentials.isEmpty());
    }

    private void showFailure(String prefix, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank() ? "未知错误" : failure.getMessage();
        viewModel.showStatus(prefix + "：" + detail, true);
        applyStatusStyle(true);
    }

    private void applyStatusStyle(boolean error) {
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        statusLabel.getStyleClass().add(error ? "status-error" : "status-success");
    }

    private void closeCards() {
        RuntimeException failure = null;
        for (int index = cardViews.size() - 1; index >= 0; index--) {
            try {
                cardViews.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cardViews.clear();
        if (credentialList != null) credentialList.getChildren().clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        mutationAction.close();
        closeCards();
        viewModel.loadingProperty().unbind();
        viewModel.mutatingProperty().unbind();
        if (loadingOverlay != null) {
            loadingOverlay.visibleProperty().unbind();
            loadingOverlay.managedProperty().unbind();
        }
        if (addButton != null) addButton.disableProperty().unbind();
        if (statusLabel != null) statusLabel.textProperty().unbind();
    }

    boolean isClosed() { return closed.get(); }
}
