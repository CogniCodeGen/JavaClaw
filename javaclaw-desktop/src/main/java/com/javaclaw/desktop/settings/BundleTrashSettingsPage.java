package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.protocol.BundleRpcContracts;

/** 已卸载 Bundle 的恢复与永久清除页面。 */
public final class BundleTrashSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final BundleTrashSettingsPresenter presenter;
    private final ListDetailPane<BundleRpcContracts.TrashEntry> masterDetail = new ListDetailPane<>();
    private final Label trashId = value();
    private final Label extension = value();
    private final Label stateLabel = value();
    private final Label version = value();
    private final Label revision = value();
    private final Label manifest = value();
    private final Label signer = value();
    private final Label removedAt = value();
    private final Label restoredRevision = value();
    private final Label purgedAt = value();
    private final Button refresh;
    private final Button restore;
    private final AsyncActionBar actions;
    private final DangerZone purge;
    private final RevisionConflictPane conflict;
    private BundleTrashSettingsState state = BundleTrashSettingsState.initial();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public BundleTrashSettingsPage(BundleSettingsGateway gateway) {
        presenter = new BundleTrashSettingsPresenter(gateway);
        refresh = action("刷新", ActionStyle.GHOST, presenter::reload);
        restore = action("恢复为新 revision", ActionStyle.SOFT, presenter::restore);
        actions = new AsyncActionBar(refresh, restore);
        purge = new DangerZone(
                "永久清除 Trash 文件", "清除后只保留最小 tombstone 防止 revision 重用；Bundle 文件不可恢复。", "永久清除", this::confirmPurge);
        conflict = new RevisionConflictPane(presenter::reload, this::describeConflict);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void configurePage() {
        Label title = new Label("Bundle Trash");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("Trash 只保存平台管理的 Bundle 文件和不可变 tombstone；恢复会重新验签并分配新的单调 revision。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.extensionId() + " · " + value.state(),
                        value -> value.version() + " · revision " + value.revision() + " · " + value.removedAt()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail
                .list()
                .setPlaceholder(components.feedback(FeedbackKind.EMPTY, "Trash 为空", "卸载第三方 Bundle 后，可恢复条目会显示在这里。"));
        masterDetail.showDetail(detail());
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection snapshot = new FormSection("Trash tombstone", "不展示服务端目录路径；状态为 RESTORED 或 PURGED 时不再允许文件动作。");
        snapshot.addField("Trash ID", trashId);
        snapshot.addField("扩展", extension);
        snapshot.addField("状态", stateLabel);
        snapshot.addField("版本", version);
        snapshot.addField("被卸载 revision", revision);
        snapshot.addField("Manifest SHA-256", manifest);
        snapshot.addField("签名密钥", signer);
        snapshot.addField("卸载时间", removedAt);
        snapshot.addField("恢复 revision", restoredRevision);
        snapshot.addField("永久清除时间", purgedAt);
        VBox detail = new VBox(12, snapshot, conflict, actions, purge);
        detail.getStyleClass().add("platform-page");
        return detail;
    }

    private void select(BundleRpcContracts.TrashEntry selected) {
        if (!rendering && selected != null && !selected.equals(state.selected().orElse(null))) {
            presenter.select(selected);
        }
    }

    private void render(BundleTrashSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(snapshot.entries());
            masterDetail.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderSelected(snapshot.selected());
        } finally {
            rendering = false;
        }
        renderActions(snapshot);
    }

    private void renderSelected(Optional<BundleRpcContracts.TrashEntry> selected) {
        trashId.setText(selected.map(BundleRpcContracts.TrashEntry::trashId).orElse("—"));
        extension.setText(
                selected.map(BundleRpcContracts.TrashEntry::extensionId).orElse("—"));
        stateLabel.setText(selected.map(value -> value.state().name()).orElse("—"));
        version.setText(selected.map(BundleRpcContracts.TrashEntry::version).orElse("—"));
        revision.setText(selected.map(value -> Long.toString(value.revision())).orElse("—"));
        manifest.setText(
                selected.map(BundleRpcContracts.TrashEntry::manifestDigest).orElse("—"));
        signer.setText(selected.map(BundleRpcContracts.TrashEntry::signingKeyId).orElse("—"));
        removedAt.setText(selected.map(value -> value.removedAt().toString()).orElse("—"));
        restoredRevision.setText(selected.flatMap(BundleRpcContracts.TrashEntry::restoredRevision)
                .map(Object::toString)
                .orElse("—"));
        purgedAt.setText(selected.flatMap(BundleRpcContracts.TrashEntry::purgedAt)
                .map(Object::toString)
                .orElse("—"));
    }

    private void renderActions(BundleTrashSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        boolean trashed = snapshot.selected()
                .filter(value -> value.state() == BundleRpcContracts.TrashState.TRASHED)
                .isPresent();
        refresh.setDisable(pending);
        restore.setDisable(pending || !trashed);
        purge.setActionDisabled(pending || !trashed);
        if (snapshot.revisionConflict()) {
            conflict.showUnknownActual(snapshot.selected()
                    .map(BundleRpcContracts.TrashEntry::revision)
                    .orElse(0L));
        } else {
            conflict.hide();
        }
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void confirmPurge() {
        BundleRpcContracts.TrashEntry entry = state.selected().orElseThrow();
        String expected = "PURGE " + entry.trashId();
        TextInputDialog dialog = new TextInputDialog();
        own(dialog);
        dialog.setTitle("永久清除 Bundle Trash");
        dialog.setHeaderText("清除后 Bundle 文件不可恢复");
        dialog.setContentText("输入 " + expected + "：");
        dialog.showAndWait().filter(expected::equals).ifPresent(presenter::purge);
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "Trash revision 已改变，请重新读取权威状态");
    }

    private Button action(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.NORMAL);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void own(javafx.scene.control.Dialog<?> dialog) {
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
