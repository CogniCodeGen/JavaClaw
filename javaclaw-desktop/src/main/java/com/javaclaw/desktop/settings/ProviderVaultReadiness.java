package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.client.CommandOptions;

/** Provider Secret 写入前非破坏性检查并重新解封 Vault；不会重置或读取任何 Secret。 */
final class ProviderVaultReadiness {
    private static final String VAULT_PAGE = "管理中心 → 安全与连接 → 密钥库";

    private ProviderVaultReadiness() {}

    static CompletionStage<Void> ensureReady(CoreSettingsGateway gateway) {
        return gateway.vaultStatus()
                .thenCompose(status -> status.state() == VaultState.READY
                        ? CompletableFuture.completedFuture(null)
                        : gateway.refreshVault().thenCompose(ProviderVaultReadiness::requireReady));
    }

    static CompletionStage<ProviderCredentialBinding> setCredential(
            CoreSettingsGateway gateway,
            ProviderEndpoint provider,
            long credentialRevision,
            char[] secret,
            CommandOptions options) {
        return ensureReady(gateway)
                .thenCompose(ignored -> gateway.setProviderCredential(provider, credentialRevision, secret, options))
                .whenComplete((binding, failure) -> Arrays.fill(secret, '\0'));
    }

    private static CompletionStage<Void> requireReady(VaultStatus status) {
        if (status.state() == VaultState.READY) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(new IllegalStateException(message(status.reason())));
    }

    private static String message(VaultLockReason reason) {
        String guidance =
                switch (reason) {
                    case SYSTEM_CREDENTIAL_UNAVAILABLE -> "请检查本地数据库是否可访问且可写";
                    case MASTER_KEY_MISSING -> "请恢复与当前凭据匹配的完整 data-v6 备份；不要重置，否则已有凭据会失效";
                    case MASTER_KEY_INVALID -> "请恢复与当前凭据匹配的完整 data-v6 备份；不要重置，否则已有凭据会失效";
                    case CLOSED -> "请重新连接或重启 App Server";
                    case NONE -> "请刷新密钥库状态后重试";
                };
        return "密钥库仍已锁定（" + SettingsLabels.vaultLockReason(reason) + "）。" + guidance + "，并在“" + VAULT_PAGE + "”中重试";
    }
}
