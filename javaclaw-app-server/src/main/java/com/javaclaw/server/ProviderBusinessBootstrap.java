package com.javaclaw.server;

import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.server.config.VaultProviderCredentialResolver;
import com.javaclaw.server.persistence.ProviderConfigurationService;
import com.javaclaw.server.persistence.ProviderContextService;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.persistence.ProviderModelPreviewService;
import com.javaclaw.server.rpc.ProviderConfigurationRpcHandlers;
import com.javaclaw.server.rpc.ProviderContextRpcHandlers;
import com.javaclaw.server.rpc.ProviderCredentialRpcHandlers;
import com.javaclaw.server.rpc.ProviderEmbeddingBindingRpcHandlers;
import com.javaclaw.server.rpc.ProviderModelDiscoveryRpcHandlers;
import com.javaclaw.server.rpc.ProviderModelPreviewRpcHandlers;
import com.javaclaw.server.rpc.ProviderVerificationRpcHandlers;
import com.javaclaw.server.rpc.RpcRouter;
import com.javaclaw.server.security.vault.ProviderModelPreviewCredentialReader;

/** Provider 业务入口装配；沿用既有基础服务、候选事务与会话资源所有权。 */
final class ProviderBusinessBootstrap {
    private ProviderBusinessBootstrap() {}

    static ProviderModelPreviewService preview(
            AppServerBootstrap.Foundation foundation, ProviderModelDiscoveryService discovery) {
        return new ProviderModelPreviewService(
                foundation.providers(),
                new ProviderModelPreviewCredentialReader(foundation.vault()),
                new ProviderModelDiscoveryAdapter(
                        new VaultProviderCredentialResolver(foundation.vault()), foundation.clock()),
                discovery);
    }

    static void register(
            RpcRouter.Builder routes,
            AppServerBootstrap.Foundation foundation,
            AppServerBootstrap.RuntimeManagement management) {
        new ProviderCredentialRpcHandlers(foundation.providerCredentials(), foundation.json()).register(routes);
        new ProviderEmbeddingBindingRpcHandlers(foundation.embeddingBinding(), foundation.json()).register(routes);
        new ProviderContextRpcHandlers(
                        new ProviderContextService(
                                foundation.database(), foundation.providers(), foundation.json(), foundation.clock()),
                        foundation.json())
                .register(routes);
        new ProviderModelDiscoveryRpcHandlers(management.providerModelDiscovery(), foundation.json()).register(routes);
        new ProviderModelPreviewRpcHandlers(management.providerModelPreview(), foundation.json()).register(routes);
        new ProviderConfigurationRpcHandlers(
                        new ProviderConfigurationService(
                                foundation.database(),
                                foundation.providers(),
                                foundation.vault().providerCredentials(),
                                foundation.vault()::metadata,
                                foundation.json(),
                                foundation.clock()),
                        foundation.json())
                .register(routes);
        new ProviderVerificationRpcHandlers(management.providerVerification(), foundation.json()).register(routes);
    }
}
