package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import com.javaclaw.desktop.component.TypedTextDangerConfirmationPolicy;
import com.javaclaw.protocol.BundleRpcContracts;

/** 已卸载扩展包的恢复与永久清除页面。 */
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
        restore = action("恢复为新版本", ActionStyle.SOFT, presenter::restore);
        actions = new AsyncActionBar(refresh, restore);
        purge = new DangerZone(
                "永久清除回收站文件",
                "清除后只保留最小删除记录，防止版本被重复使用；扩展包文件不可恢复。",
                "永久清除",
                new TypedTextDangerConfirmationPolicy(this, this::purgeConfirmation),
                () -> presenter.purge(purgeConfirmation()));
        conflict = new RevisionConflictPane(presenter::reload, this::describeConflict);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
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
        Label title = new Label("扩展包回收站");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("回收站保存已卸载的扩展包文件和不可修改的删除记录；恢复时会重新验证签名并分配新版本。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.extensionId() + " · " + SettingsLabels.trashState(value.state()),
                        value -> value.version() + " · 版本 " + value.revision() + " · " + value.removedAt()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "回收站为空", "卸载第三方扩展包后，可恢复条目会显示在这里。"));
        masterDetail.showDetail(detail());
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection snapshot = new FormSection("回收站记录", "不展示服务端目录路径；已恢复或已永久清除的记录不再允许文件操作。");
        snapshot.addField("回收站标识", trashId);
        snapshot.addField("扩展", extension);
        snapshot.addField("状态", stateLabel);
        snapshot.addField("版本", version);
        snapshot.addField("被卸载版本", revision);
        snapshot.addField("清单指纹（SHA-256）", manifest);
        snapshot.addField("签名密钥", signer);
        snapshot.addField("卸载时间", removedAt);
        snapshot.addField("恢复版本", restoredRevision);
        snapshot.addField("永久清除时间", purgedAt);
        VBox detail = new VBox(12, snapshot, conflict, purge);
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
        stateLabel.setText(
                selected.map(value -> SettingsLabels.trashState(value.state())).orElse("—"));
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

    private String purgeConfirmation() {
        return state.selected().map(entry -> "PURGE " + entry.trashId()).orElse("");
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "回收站版本已改变，请重新读取权威状态");
    }

    private Button action(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.NORMAL);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
