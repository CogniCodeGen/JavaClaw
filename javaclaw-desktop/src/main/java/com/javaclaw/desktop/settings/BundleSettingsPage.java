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
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.desktop.component.TypedTextDangerConfirmationPolicy;
import com.javaclaw.protocol.BundleRpcContracts;

/** 第三方扩展包的附件暂存、权限审阅、健康与生命周期管理页。 */
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
        choose = action("选择并上传扩展包", ActionStyle.SOFT, this::chooseBundle);
        install = action("确认并安装", ActionStyle.PRIMARY, () -> confirmStage(false));
        upgrade = action("确认并升级", ActionStyle.PRIMARY, () -> confirmStage(true));
        discard = action("丢弃待安装文件", ActionStyle.GHOST, presenter::discardDraft);
        probe = action("健康检查", ActionStyle.SOFT, presenter::probe);
        toggle = action("启用", ActionStyle.SOFT, presenter::toggleEnabled);
        actions = new AsyncActionBar(choose, install, upgrade, discard, probe, toggle);
        uninstall = new DangerZone(
                "卸载到回收站",
                "卸载会实时阻止新调用并保留可恢复文件；不会永久清除扩展包。",
                "卸载扩展包",
                new TypedTextDangerConfirmationPolicy(this, this::uninstallConfirmation),
                presenter::uninstall);
        conflict = new RevisionConflictPane(this::reloadAfterConflict, this::describeConflict);
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
        Label title = new Label("第三方扩展包");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("扩展包会先作为 JavaClaw 服务附件上传；请逐项核对签名、摘要和进程权限，再安装或升级。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.displayName() + " · " + SettingsLabels.extensionState(value.state()),
                        value -> value.id() + " · 版本 " + value.revision() + " · " + value.version()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无第三方扩展包", "选择已签名的扩展包文件，上传后审阅权限。"));
        masterDetail.showDetail(detail());
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection installed = new FormSection("安装快照", "所有字段来自服务端权威目录；页面不读取安装路径或扩展进程文件。");
        installed.addField("扩展包", identity);
        installed.addField("状态", lifecycle);
        installed.addField("包版本", version);
        installed.addField("配置版本", revision);
        installed.addField("清单指纹（SHA-256）", manifest);
        installed.addField("签名密钥", signer);
        installed.addField("签名指纹", fingerprint);
        installed.addField("贡献点", contributions);
        FormSection healthSection = new FormSection("健康与退避", "失败、退避和隔离均由服务端持久化，页面不会推测健康状态。");
        healthSection.addField("健康状态", health);
        healthSection.addField("连续失败", failures);
        healthSection.addField("下次重试", retry);
        healthSection.addField("最近失败", lastFailure);
        FormSection staging = new FormSection("待确认的安装文件", "点击安装或升级即表示已核对下列签名、摘要和权限请求。");
        staging.addField("扩展", stagingIdentity);
        staging.addField("附件", stagingAttachment);
        staging.addField("清单指纹（SHA-256）", stagingManifest);
        staging.addField("签名密钥 / 指纹", stagingSigner);
        staging.addField("权限审阅", stagingPermissions);
        VBox detail = new VBox(12, installed, healthSection, staging, conflict, uninstall);
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
        lifecycle.setText(selected.map(value -> SettingsLabels.extensionState(value.state()))
                .orElse("—"));
        version.setText(selected.map(BundleRpcContracts.Bundle::version).orElse("—"));
        revision.setText(selected.map(value -> Long.toString(value.revision())).orElse("—"));
        manifest.setText(selected.map(BundleRpcContracts.Bundle::manifestDigest).orElse("—"));
        signer.setText(selected.map(BundleRpcContracts.Bundle::signingKeyId).orElse("—"));
        fingerprint.setText(
                selected.map(BundleRpcContracts.Bundle::signingKeyFingerprint).orElse("—"));
        contributions.setText(selected.map(value -> value.contributionKinds().stream()
                        .map(SettingsLabels::contributionKind)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("、")))
                .orElse("—"));
        health.setText(
                selected.map(value -> SettingsLabels.bundleHealth(value.health().state()))
                        .orElse("—"));
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
        chooser.setTitle("选择签名 JavaClaw 扩展包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JavaClaw 扩展包 ZIP", "*.zip"));
        File selected =
                chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) {
            presenter.stage(selected.toPath());
        }
    }

    private void confirmStage(boolean upgradeAction) {
        BundleRpcContracts.StageResult staged = state.staging().orElseThrow();
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(upgradeAction ? "确认原子升级" : "确认安装扩展包");
        dialog.setHeaderText(staged.displayName() + " · " + staged.version());
        dialog.setContentText("清单指纹：" + staged.manifestDigest() + "\n签名指纹：" + staged.signingKeyFingerprint() + "\n权限："
                + permissions(staged.permissions()));
        PlatformDialogs.style(dialog, this);
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            if (upgradeAction) {
                presenter.upgrade();
            } else {
                presenter.install();
            }
        }
    }

    private String uninstallConfirmation() {
        return state.selected().map(bundle -> "UNINSTALL " + bundle.id()).orElse("");
    }

    private void reloadAfterConflict() {
        presenter.discardDraft();
        presenter.reload();
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "服务端版本已改变；待安装文件已保留，可丢弃后重新读取并比较摘要");
    }

    private Button action(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.NORMAL);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static String permissions(BundleRpcContracts.PermissionReview value) {
        return "工作区：读取=" + SettingsLabels.yesNo(value.workspaceRead())
                + "、写入=" + SettingsLabels.yesNo(value.workspaceWrite())
                + "、删除=" + SettingsLabels.yesNo(value.allowDelete())
                + "；网络：" + value.networkHosts()
                + ":" + value.networkPorts()
                + "；可执行文件：" + value.executableName()
                + "；超时：" + value.maxRunTime()
                + "；内存：" + value.memoryBytes()
                + " 字节";
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
