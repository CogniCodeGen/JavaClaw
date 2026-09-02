package com.javaclaw.desktop.settings;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

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

/** Ed25519 Trust Key 的 Attachment 导入、指纹确认和实时撤销页面。 */
public final class TrustKeySettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final TrustKeySettingsPresenter presenter;
    private final ListDetailPane<BundleRpcContracts.TrustKey> masterDetail = new ListDetailPane<>();
    private final TextField keyId = new TextField();
    private final Label draftFile = value();
    private final Label draftAttachment = value();
    private final Label draftFingerprint = value();
    private final Label selectedId = value();
    private final Label selectedState = value();
    private final Label selectedFingerprint = value();
    private final Label selectedAttachment = value();
    private final Label selectedRevision = value();
    private final Label selectedUpdated = value();
    private final Button choose;
    private final Button confirmImport;
    private final Button discard;
    private final AsyncActionBar actions;
    private final DangerZone revoke;
    private final RevisionConflictPane conflict;
    private TrustKeySettingsState state = TrustKeySettingsState.initial();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public TrustKeySettingsPage(BundleSettingsGateway gateway) {
        presenter = new TrustKeySettingsPresenter(gateway);
        choose = action("选择并上传公钥", ActionStyle.SOFT, this::choosePublicKey);
        confirmImport = action("核对指纹并导入", ActionStyle.PRIMARY, this::confirmImport);
        discard = action("丢弃草稿", ActionStyle.GHOST, presenter::discardDraft);
        actions = new AsyncActionBar(choose, confirmImport, discard);
        revoke = new DangerZone(
                "撤销 Trust Key", "撤销后该密钥不能继续验签，所有由其签名的第三方 Bundle 会被服务端实时禁用。", "撤销并禁用关联 Bundle", this::confirmRevoke);
        conflict = new RevisionConflictPane(this::reloadAfterConflict, this::describeConflict);
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
        return state.dirty();
    }

    @Override
    public void warnUnsavedChanges() {
        presenter.warnUnsavedChanges();
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configurePage() {
        Label title = new Label("信任公钥");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("公钥从 Core Attachment 导入；页面只展示不可逆指纹和元数据，不返回公钥文件路径或私钥内容。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.id() + " · " + value.state(),
                        value -> "revision " + value.revision() + " · " + shortFingerprint(value.fingerprint())));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail
                .list()
                .setPlaceholder(
                        components.feedback(FeedbackKind.EMPTY, "暂无 Trust Key", "导入 Ed25519 公钥并核对完整 SHA-256 指纹。"));
        masterDetail.showDetail(detail());
        keyId.textProperty().addListener((observable, previous, value) -> updateKeyId(value));
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection current = new FormSection("密钥状态", "撤销是实时 kill switch；活动或后续 Bundle 调用不能绕过此状态。");
        current.addField("Key ID", selectedId);
        current.addField("状态", selectedState);
        current.addField("完整指纹", selectedFingerprint);
        current.addField("来源 Attachment", selectedAttachment);
        current.addField("Revision", selectedRevision);
        current.addField("更新时间", selectedUpdated);
        FormSection draft = new FormSection("导入草稿", "先上传，再核对规范 DER 的完整 SHA-256；导入按钮会再次显示确认内容。");
        keyId.setPromptText("manifest signingKeyId");
        keyId.setAccessibleText("Trust Key 标识");
        draft.addField("Key ID", keyId);
        draft.addField("文件名", draftFile);
        draft.addField("Attachment", draftAttachment);
        draft.addField("完整指纹", draftFingerprint);
        VBox content = new VBox(12, current, draft, conflict, actions, revoke);
        content.getStyleClass().add("platform-page");
        return content;
    }

    private void select(BundleRpcContracts.TrustKey selected) {
        if (!rendering && selected != null && !selected.equals(state.selected().orElse(null))) {
            presenter.select(selected);
        }
    }

    private void updateKeyId(String value) {
        if (!rendering) {
            presenter.updateKeyId(value);
        }
    }

    private void render(TrustKeySettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(snapshot.keys());
            masterDetail.list().getSelectionModel().select(snapshot.selected().orElse(null));
            keyId.setText(snapshot.keyId());
            renderSelected(snapshot.selected());
            renderDraft(snapshot.draft());
        } finally {
            rendering = false;
        }
        renderActions(snapshot);
    }

    private void renderSelected(Optional<BundleRpcContracts.TrustKey> selected) {
        selectedId.setText(selected.map(BundleRpcContracts.TrustKey::id).orElse("—"));
        selectedState.setText(selected.map(value -> value.state().name()).orElse("—"));
        selectedFingerprint.setText(
                selected.map(BundleRpcContracts.TrustKey::fingerprint).orElse("—"));
        selectedAttachment.setText(
                selected.map(BundleRpcContracts.TrustKey::attachmentDigest).orElse("—"));
        selectedRevision.setText(
                selected.map(value -> Long.toString(value.revision())).orElse("—"));
        selectedUpdated.setText(
                selected.map(value -> value.updatedAt().toString()).orElse("—"));
    }

    private void renderDraft(Optional<TrustKeyImportDraft> draft) {
        draftFile.setText(draft.map(TrustKeyImportDraft::fileName).orElse("—"));
        draftAttachment.setText(draft.map(value -> value.attachment().digest()).orElse("—"));
        draftFingerprint.setText(draft.map(TrustKeyImportDraft::fingerprint).orElse("—"));
    }

    private void renderActions(TrustKeySettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        boolean prepared = snapshot.draft().isPresent();
        choose.setDisable(pending || prepared);
        confirmImport.setDisable(pending || !prepared || snapshot.keyId().isBlank());
        discard.setDisable(pending || !snapshot.dirty());
        keyId.setDisable(pending);
        boolean active = snapshot.selected()
                .filter(value -> value.state() == BundleRpcContracts.TrustState.ACTIVE)
                .isPresent();
        revoke.setActionDisabled(pending || snapshot.dirty() || !active);
        if (snapshot.revisionConflict()) {
            conflict.showUnknownActual(snapshot.selected()
                    .map(BundleRpcContracts.TrustKey::revision)
                    .orElse(0L));
        } else {
            conflict.hide();
        }
        status(snapshot, pending);
    }

    private void status(TrustKeySettingsState snapshot, boolean pending) {
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else if (snapshot.dirty()) {
            actions.show(ActionState.DIRTY, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void choosePublicKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Ed25519 公钥");
        chooser.getExtensionFilters()
                .addAll(
                        new FileChooser.ExtensionFilter("Ed25519 public key", "*.pub", "*.der", "*.txt"),
                        new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File selected =
                chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) {
            presenter.prepare(selected.toPath());
        }
    }

    private void confirmImport() {
        TrustKeyImportDraft draft = state.draft().orElseThrow();
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        own(dialog);
        dialog.setTitle("确认 Trust Key 指纹");
        dialog.setHeaderText("Key ID: " + state.keyId().strip());
        dialog.setContentText("请与发布者提供的指纹逐字核对：\n" + draft.fingerprint() + "\n\n来源 Attachment: "
                + draft.attachment().digest());
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.importPrepared();
        }
    }

    private void confirmRevoke() {
        BundleRpcContracts.TrustKey key = state.selected().orElseThrow();
        String expected = "REVOKE " + key.id();
        TextInputDialog dialog = new TextInputDialog();
        own(dialog);
        dialog.setTitle("撤销 Trust Key");
        dialog.setHeaderText("关联 Bundle 会被实时禁用");
        dialog.setContentText("输入 " + expected + "：");
        dialog.showAndWait().filter(expected::equals).ifPresent(ignored -> presenter.revoke());
    }

    private void reloadAfterConflict() {
        presenter.discardDraft();
        presenter.reload();
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "服务端 Trust Key revision 已改变；草稿保留，可丢弃后重新读取");
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

    private static String shortFingerprint(String value) {
        return value.substring(0, 12) + "…";
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
