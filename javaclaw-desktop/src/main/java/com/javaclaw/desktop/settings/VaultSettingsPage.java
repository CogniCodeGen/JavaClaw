package com.javaclaw.desktop.settings;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.desktop.component.CopyableTextField;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 密钥库脱敏状态与主密钥契约可用性页面。 */
public final class VaultSettingsPage implements ManagedSettingsPage {
    private static final String RESET_CONFIRMATION = "RESET VAULT";

    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final VaultSettingsPresenter presenter;
    private final VBox content = components.page("密钥库");
    private final Label state = value();
    private final Label reason = value();
    private final Label credentialCount = value();
    private final Label cleanup = value();
    private final Label checkedAt = value();
    private final Label receipt = value();
    private final Label error = new Label();
    private final Button refresh;
    private final Button rotate;
    private final TextField resetExpected = new CopyableTextField(RESET_CONFIRMATION, "密钥库重置确认语句，可选择并复制");
    private final TextField resetConfirmation = new TextField();
    private final DangerZone resetZone;
    private VaultSettingsState latest = VaultSettingsState.initial();

    /**
     * 创建密钥库状态页。
     *
     * @param gateway SDK 异步边界
     */
    public VaultSettingsPage(CoreSettingsGateway gateway) {
        presenter = new VaultSettingsPresenter(gateway);
        refresh = components.action("刷新并重新解封", ActionStyle.SOFT, ActionSize.NORMAL);
        refresh.setOnAction(event -> presenter.refresh());
        rotate = components.action("轮换主密钥", ActionStyle.PRIMARY, ActionSize.NORMAL);
        rotate.setOnAction(event -> presenter.rotateMasterKey());
        resetZone = new DangerZone(
                "永久重置密钥库",
                "永久清除所有密钥，并使旧凭据引用失效。复制或输入确认语句后才可执行。",
                "永久重置密钥库",
                ignored -> RESET_CONFIRMATION.equals(resetConfirmation.getText()),
                this::resetVault);
        buildLayout();
        resetConfirmation.textProperty().addListener((ignored, previous, value) -> updateActionAvailability(latest));
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return content;
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

    private void buildLayout() {
        Label hint = new Label("密钥只能写入、轮换、清除和查看“已配置”元数据；任何页面都不能读取、复制或导出明文。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        FormSection status = new FormSection("运行状态", "密钥库锁定时，管理中心仍可打开；模型服务、MCP、网站和定时任务会拒绝运行。");
        status.addField("状态", state);
        status.addField("锁定原因", reason);
        status.addField("凭据数量", credentialCount);
        status.addField("旧主密钥清理", cleanup);
        status.addField("检查时间", checkedAt);
        status.addField("最近管理动作", receipt);
        FormSection masterKey = new FormSection("主密钥管理", "轮换和重置必须由 JavaClaw 服务完成安全提交和危险确认，桌面端不直接访问系统凭据。");
        Label rotationHint = new Label("轮换会重新加密全部记录，但不会改变凭据引用或业务版本。");
        rotationHint.setWrapText(true);
        rotationHint.getStyleClass().add("sec-hint");
        masterKey.addFullWidth(new HBox(8, rotate));
        masterKey.addFullWidth(rotationHint);
        resetConfirmation.setPromptText("粘贴或逐字输入上方确认语句");
        resetConfirmation.setAccessibleText("密钥库重置危险确认");
        FormSection reset = new FormSection("危险确认", "上方确认语句可选中复制；系统仍会精确匹配大小写、空格和标点，避免误触导致全部密钥失效。");
        reset.addField("需要输入", resetExpected);
        reset.addField("确认文本", resetConfirmation);
        reset.addFullWidth(resetZone);
        error.setWrapText(true);
        error.getStyleClass().add("platform-action-error");
        content.getChildren().addAll(hint, refresh, status, masterKey, reset, error);
    }

    private void render(VaultSettingsState snapshot) {
        latest = snapshot;
        refresh.setDisable(
                snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING);
        error.setText(snapshot.phase() == SettingsLoadState.ERROR ? snapshot.message() : "");
        error.setVisible(!error.getText().isBlank());
        error.setManaged(error.isVisible());
        snapshot.status().ifPresentOrElse(this::renderStatus, this::clearStatus);
        receipt.setText(snapshot.receipt()
                .map(value -> SettingsLabels.vaultManagementAction(value.action()) + " · "
                        + value.affectedCredentialCount() + " 条 · " + value.completedAt())
                .orElse("—"));
        updateActionAvailability(snapshot);
    }

    private void renderStatus(VaultStatus status) {
        state.setText(SettingsLabels.vaultState(status.state()));
        reason.setText(SettingsLabels.vaultLockReason(status.reason()));
        credentialCount.setText(Long.toString(status.credentialCount()));
        cleanup.setText(status.oldKeyCleanupPending() ? "待完成" : "无需处理");
        checkedAt.setText(status.checkedAt().toString());
    }

    private void clearStatus() {
        state.setText("尚未读取");
        reason.setText("—");
        credentialCount.setText("—");
        cleanup.setText("—");
        checkedAt.setText("—");
    }

    private void updateActionAvailability(VaultSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        boolean ready = snapshot.status().map(VaultStatus::state).orElse(VaultState.LOCKED) == VaultState.READY;
        rotate.setDisable(pending || !ready);
        resetZone.setActionDisabled(pending || !resetConfirmation.getText().equals(RESET_CONFIRMATION));
    }

    private void resetVault() {
        String confirmation = resetConfirmation.getText();
        resetConfirmation.clear();
        presenter.reset(confirmation);
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
