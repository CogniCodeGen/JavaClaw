package com.javaclaw.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.VaultState;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.ProviderConfigurationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用临时 H2 验证生产组合根的本地 Vault 路径，不构造模型客户端或写入系统钥匙串。 */
class LocalVaultFoundationTest {
    @TempDir
    Path directory;

    @Test
    void 新建服务将密钥和模型完整保存到本地并在重启后保持可用() throws Exception {
        Path dataRoot = directory.resolve("data-v6");
        ProviderConfigurationResult saved;
        try (StartupCloseStack owned = new StartupCloseStack()) {
            var foundation = PlatformFoundationFactory.createLocal(dataRoot, Clock.systemUTC(), required -> {});
            AppServerBootstrap.ownFoundation(owned, foundation);
            assertEquals(VaultState.READY, foundation.vault().status().state());
            var configuration = configuration();
            var identity = new CommandIdentity(
                    ProviderConfigurationRpcContracts.SAVE_METHOD, "local-provider-save", 0, "a".repeat(64));
            byte[] secret = "local-database-provider-test-key".getBytes(StandardCharsets.UTF_8);
            saved = configurations(foundation).save(identity, configuration, secret);
            assertEquals(ProviderLifecycle.ACTIVE, saved.provider().lifecycle());
            for (byte value : secret) {
                assertEquals(0, value);
            }
            assertEquals(1, localKeyCount(foundation));
        }
        try (StartupCloseStack owned = new StartupCloseStack()) {
            var restarted = PlatformFoundationFactory.createLocal(dataRoot, Clock.systemUTC(), required -> {});
            AppServerBootstrap.ownFoundation(owned, restarted);
            assertEquals(VaultState.READY, restarted.vault().status().state());
            assertEquals(
                    saved.credential(),
                    restarted.vault().metadata(saved.credential().orElseThrow().reference()));
            assertEquals(
                    "local-database-provider-test-key",
                    restarted
                            .vault()
                            .use(
                                    saved.credential().orElseThrow().reference(),
                                    bytes -> new String(bytes, StandardCharsets.UTF_8)));
            assertEquals(1, localKeyCount(restarted));
        }
    }

    @Test
    void 显式锁定注入仍保持锁定且不创建本地主密钥() throws Exception {
        try (StartupCloseStack owned = new StartupCloseStack()) {
            var foundation = PlatformFoundationFactory.create(
                    directory.resolve("data-v6"), Clock.systemUTC(), new LockedMasterKeyProtector(), required -> {});
            AppServerBootstrap.ownFoundation(owned, foundation);
            assertEquals(VaultState.LOCKED, foundation.vault().status().state());
            assertEquals(0, localKeyCount(foundation));
        }
    }

    private static long localKeyCount(AppServerBootstrap.Foundation foundation) throws Exception {
        return new H2Transactions(foundation.database()).execute(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM CORE.VAULT_LOCAL_MASTER_KEY")) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        });
    }

    private static ProviderConfiguration configuration() {
        var spec = ProviderEndpointTestFixtures.apiKeyChat("本地保存测试", ProviderAdapter.OPENAI_COMPATIBLE, "test-chat");
        return new ProviderConfiguration(
                "local-provider",
                0,
                ProviderConnectionSpec.from(spec),
                spec.models(),
                ProviderLifecycle.ACTIVE,
                ProviderCredentialChange.REPLACE,
                0);
    }

    private static ProviderConfigurationService configurations(AppServerBootstrap.Foundation foundation) {
        return new ProviderConfigurationService(
                foundation.database(),
                foundation.providers(),
                foundation.vault().providerCredentials(),
                foundation.vault()::metadata,
                foundation.json(),
                foundation.clock());
    }
}
