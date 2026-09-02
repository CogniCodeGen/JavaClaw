package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.client.CommandOptions;

/** Secret Vault 状态页的异步 Presenter。 */
public final class VaultSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<VaultSettingsState> listener = ignored -> {};
    private VaultSettingsState state = VaultSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public VaultSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<VaultSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取脱敏 Vault 状态。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(new VaultSettingsState(
                SettingsLoadState.LOADING, state.status(), state.receipt(), "正在读取 Vault 状态…", epoch));
        gateway.vaultStatus().whenComplete((status, failure) -> completeStatus(epoch, status, failure, ""));
    }

    /** 系统凭据设施恢复后重新尝试解封主密钥。 */
    public void refresh() {
        long epoch = state.epoch() + 1;
        publish(new VaultSettingsState(
                SettingsLoadState.LOADING, state.status(), state.receipt(), "正在重新解封 Vault 主密钥…", epoch));
        gateway.refreshVault().whenComplete((status, failure) -> completeStatus(epoch, status, failure, "Vault 状态已刷新"));
    }

    /** 原子轮换主密钥并保持 CredentialRef 不变。 */
    public void rotateMasterKey() {
        long epoch = state.epoch() + 1;
        publish(new VaultSettingsState(
                SettingsLoadState.SAVING, state.status(), state.receipt(), "正在原子轮换 Vault 主密钥…", epoch));
        gateway.rotateVaultMasterKey(CommandOptions.create(0))
                .whenComplete((receipt, failure) -> completeManagement(epoch, receipt, failure));
    }

    /**
     * 永久重置 Vault。
     *
     * @param confirmation 必须精确为 RESET VAULT
     */
    public void reset(String confirmation) {
        if (!"RESET VAULT".equals(confirmation)) {
            publish(new VaultSettingsState(
                    SettingsLoadState.ERROR, state.status(), state.receipt(), "Vault reset 确认文本不匹配", state.epoch()));
            return;
        }
        long epoch = state.epoch() + 1;
        publish(new VaultSettingsState(
                SettingsLoadState.SAVING, state.status(), state.receipt(), "正在永久重置 Vault…", epoch));
        gateway.resetVault(confirmation, CommandOptions.create(0))
                .whenComplete((receipt, failure) -> completeManagement(epoch, receipt, failure));
    }

    private void completeStatus(long epoch, VaultStatus status, Throwable failure, String message) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new VaultSettingsState(
                    SettingsLoadState.ERROR,
                    state.status(),
                    state.receipt(),
                    SettingsFailures.message(failure),
                    epoch));
        } else {
            publish(new VaultSettingsState(
                    SettingsLoadState.READY, Optional.of(status), state.receipt(), message, epoch));
        }
    }

    private void completeManagement(long epoch, VaultManagementReceipt receipt, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new VaultSettingsState(
                    SettingsLoadState.ERROR,
                    state.status(),
                    state.receipt(),
                    SettingsFailures.message(failure),
                    epoch));
            return;
        }
        publish(new VaultSettingsState(
                SettingsLoadState.READY,
                state.status(),
                Optional.of(receipt),
                receipt.action() + " 已完成，影响 " + receipt.affectedCredentialCount() + " 条凭据",
                epoch));
        reload();
    }

    private void publish(VaultSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
