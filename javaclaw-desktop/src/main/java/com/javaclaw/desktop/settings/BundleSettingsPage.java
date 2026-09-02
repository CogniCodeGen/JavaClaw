package com.javaclaw.desktop.settings;

import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
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

/** 第三方 Bundle 的 Attachment staging、权限审阅、健康与生命周期管理页。 */
public final class BundleSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final BundleSettingsPresenter presenter;
    private final ListDetailPane<BundleRpcContracts.Bundle> masterDetail = new ListDetailPane<>();
    private final Label identity = value();
    private final Label lifecycle = value();
    private final Label version = value();
    private final Label revision = value();
    private final Label manifest = value();
    private final Label signer = value();
    private final Label fingerprint = value();
    private final Label contributions = value();
    private final Label health = value();
    private final Label failures = value();
    private final Label retry = value();
    private final Label lastFailure = value();
    private final Label stagingIdentity = value();
    private final Label stagingAttachment = value();
    private final Label stagingManifest = value();
    private final Label stagingSigner = value();
    private final Label stagingPermissions = value();
    private final Button choose;
    private final Button install;
    private final Button upgrade;
    private final Button discard;
    private final Button probe;
    private final Button toggle;
    private final AsyncActionBar actions;
    private final DangerZone uninstall;
    private final RevisionConflictPane conflict;
    private BundleSettingsState state = BundleSettingsState.initial();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public BundleSettingsPage(BundleSettingsGateway gateway) {
        presenter = new BundleSettingsPresenter(gateway);
        choose = action("选择并上传 Bundle", ActionStyle.SOFT, this::chooseBundle);
        install = action("确认并安装", ActionStyle.PRIMARY, () -> confirmStage(false));
        upgrade = action("确认并升级", ActionStyle.PRIMARY, () -> confirmStage(true));
        discard = action("丢弃 staging", ActionStyle.GHOST, presenter::discardDraft);
        probe = action("健康检查", ActionStyle.SOFT, presenter::probe);
        toggle = action("启用", ActionStyle.SOFT, presenter::toggleEnabled);
        actions = new AsyncActionBar(choose, install, upgrade, discard, probe, toggle);
        uninstall =
                new DangerZone("卸载到 Trash", "卸载会实时阻止新调用并保留可恢复文件；不会永久清除 Bundle。", "卸载 Bundle", this::confirmUninstall);
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
        Label title = new Label("第三方 Bundle");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("Bundle 只能从 Core Attachment staging；请逐项核对签名、摘要和进程权限后安装或原子升级。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.displayName() + " · " + value.state(),
                        value -> value.id() + " · v" + value.revision() + " · " + value.version()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail
                .list()
                .setPlaceholder(
                        components.feedback(FeedbackKind.EMPTY, "暂无第三方 Bundle", "选择签名 Bundle 文件进行 staging 和权限审阅。"));
        masterDetail.showDetail(detail());
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection installed = new FormSection("安装快照", "所有字段来自服务端权威目录；页面不读取安装路径或扩展进程文件。");
        installed.addField("Bundle", identity);
        installed.addField("状态", lifecycle);
        installed.addField("版本", version);
        installed.addField("Revision", revision);
        installed.addField("Manifest SHA-256", manifest);
        installed.addField("签名密钥", signer);
        installed.addField("签名指纹", fingerprint);
        installed.addField("贡献点", contributions);
        FormSection healthSection = new FormSection("健康与退避", "失败、退避和隔离均由服务端持久化，页面不会推测健康状态。");
        healthSection.addField("健康状态", health);
        healthSection.addField("连续失败", failures);
        healthSection.addField("下次重试", retry);
        healthSection.addField("最近失败", lastFailure);
        FormSection staging = new FormSection("待确认 staging", "点击安装或升级即表示已核对下列签名、摘要和权限请求。");
        staging.addField("扩展", stagingIdentity);
        staging.addField("Attachment", stagingAttachment);
        staging.addField("Manifest SHA-256", stagingManifest);
        staging.addField("签名密钥 / 指纹", stagingSigner);
        staging.addField("权限审阅", stagingPermissions);
        VBox detail = new VBox(12, installed, healthSection, staging, conflict, actions, uninstall);
        detail.getStyleClass().add("platform-page");
        return detail;
    }

    private void select(BundleRpcContracts.Bundle selected) {
        if (!rendering && selected != null && !selected.equals(state.selected().orElse(null))) {
            presenter.select(selected);
        }
    }

    private void render(BundleSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(snapshot.bundles());
            masterDetail.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderInstalled(snapshot.selected());
            renderStaging(snapshot.staging());
        } finally {
            rendering = false;
        }
        renderActions(snapshot);
    }

    private void renderInstalled(Optional<BundleRpcContracts.Bundle> selected) {
        identity.setText(
                selected.map(value -> value.displayName() + " · " + value.id()).orElse("尚未安装"));
        lifecycle.setText(selected.map(BundleRpcContracts.Bundle::state).orElse("—"));
        version.setText(selected.map(BundleRpcContracts.Bundle::version).orElse("—"));
        revision.setText(selected.map(value -> Long.toString(value.revision())).orElse("—"));
        manifest.setText(selected.map(BundleRpcContracts.Bundle::manifestDigest).orElse("—"));
        signer.setText(selected.map(BundleRpcContracts.Bundle::signingKeyId).orElse("—"));
        fingerprint.setText(
                selected.map(BundleRpcContracts.Bundle::signingKeyFingerprint).orElse("—"));
        contributions.setText(selected.map(value -> String.join(", ", value.contributionKinds()))
                .orElse("—"));
        health.setText(selected.map(value -> value.health().state().name()).orElse("—"));
        failures.setText(selected.map(value -> Integer.toString(value.health().failureCount()))
                .orElse("—"));
        retry.setText(selected.flatMap(value -> value.health().nextRetryAt())
                .map(Object::toString)
                .orElse("—"));
        lastFailure.setText(
                selected.flatMap(value -> value.health().lastFailure()).orElse("—"));
    }

    private void renderStaging(Optional<BundleRpcContracts.StageResult> staged) {
        stagingIdentity.setText(staged.map(value -> value.displayName() + " · " + value.extensionId())
                .orElse("—"));
        stagingAttachment.setText(
                staged.map(value -> value.attachment().digest()).orElse("—"));
        stagingManifest.setText(
                staged.map(BundleRpcContracts.StageResult::manifestDigest).orElse("—"));
        stagingSigner.setText(staged.map(value -> value.signingKeyId() + " · " + value.signingKeyFingerprint())
                .orElse("—"));
        stagingPermissions.setText(
                staged.map(value -> permissions(value.permissions())).orElse("—"));
    }

    private void renderActions(BundleSettingsState snapshot) {
        boolean pending = pending(snapshot);
        boolean hasStage = snapshot.staging().isPresent();
        boolean sameInstalled = snapshot.staging()
                .flatMap(staged -> snapshot.bundles().stream()
                        .filter(bundle -> bundle.id().equals(staged.extensionId()))
                        .findFirst())
                .isPresent();
        renderStagingActions(pending, hasStage, sameInstalled);
        renderLifecycleActions(snapshot, pending, hasStage);
        conflict(snapshot);
        status(snapshot, pending);
    }

    private void renderStagingActions(boolean pending, boolean hasStage, boolean sameInstalled) {
        choose.setDisable(pending || hasStage);
        install.setDisable(pending || !hasStage || sameInstalled);
        upgrade.setDisable(pending || !hasStage || !sameInstalled);
        discard.setDisable(pending || !hasStage);
    }

    private void renderLifecycleActions(BundleSettingsState snapshot, boolean pending, boolean hasStage) {
        boolean noSelection = snapshot.selected().isEmpty();
        probe.setDisable(pending || noSelection || hasStage);
        toggle.setDisable(pending || noSelection || hasStage);
        boolean removable = snapshot.selected()
                .filter(value -> Set.of("INSTALLED", "DISABLED", "QUARANTINED").contains(value.state()))
                .isPresent();
        uninstall.setActionDisabled(pending || !removable || hasStage);
        toggle.setText(
                snapshot.selected()
                                .filter(value -> "ENABLED".equals(value.state()))
                                .isPresent()
                        ? "停用"
                        : "启用");
    }

    private static boolean pending(BundleSettingsState snapshot) {
        return snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
    }

    private void conflict(BundleSettingsState snapshot) {
        if (snapshot.revisionConflict()) {
            conflict.showUnknownActual(
                    snapshot.selected().map(BundleRpcContracts.Bundle::revision).orElse(0L));
        } else {
            conflict.hide();
        }
    }

    private void status(BundleSettingsState snapshot, boolean pending) {
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

    private void chooseBundle() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择签名 JavaClaw Bundle");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JavaClaw Bundle ZIP", "*.zip"));
        File selected =
                chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) {
            presenter.stage(selected.toPath());
        }
    }

    private void confirmStage(boolean upgradeAction) {
        BundleRpcContracts.StageResult staged = state.staging().orElseThrow();
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        own(dialog);
        dialog.setTitle(upgradeAction ? "确认原子升级" : "确认安装 Bundle");
        dialog.setHeaderText(staged.displayName() + " · " + staged.version());
        dialog.setContentText("Manifest: " + staged.manifestDigest() + "\n签名指纹: " + staged.signingKeyFingerprint()
                + "\n权限: " + permissions(staged.permissions()));
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            if (upgradeAction) {
                presenter.upgrade();
            } else {
                presenter.install();
            }
        }
    }

    private void confirmUninstall() {
        BundleRpcContracts.Bundle bundle = state.selected().orElseThrow();
        String expected = "UNINSTALL " + bundle.id();
        TextInputDialog dialog = new TextInputDialog();
        own(dialog);
        dialog.setTitle("卸载第三方 Bundle");
        dialog.setHeaderText("Bundle 会进入可恢复 Trash，不会永久清除");
        dialog.setContentText("输入 " + expected + "：");
        dialog.showAndWait().filter(expected::equals).ifPresent(ignored -> presenter.uninstall());
    }

    private void reloadAfterConflict() {
        presenter.discardDraft();
        presenter.reload();
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "服务端 revision 已改变；staging 保留，可丢弃后重新读取再比较摘要");
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

    private static String permissions(BundleRpcContracts.PermissionReview value) {
        return "Workspace read=" + value.workspaceRead()
                + ", write=" + value.workspaceWrite()
                + ", delete=" + value.allowDelete()
                + "; network=" + value.networkHosts()
                + ":" + value.networkPorts()
                + "; executable=" + value.executableName()
                + "; timeout=" + value.maxRunTime()
                + "; memory=" + value.memoryBytes()
                + " bytes";
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
