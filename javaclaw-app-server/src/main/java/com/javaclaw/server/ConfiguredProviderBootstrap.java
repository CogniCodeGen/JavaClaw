package com.javaclaw.server;

import java.util.Objects;

import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.model.ProviderEmbeddingAdapterFactory;
import com.javaclaw.model.ProviderModelAdapterFactory;
import com.javaclaw.server.config.ProviderEmbeddingRegistry;
import com.javaclaw.server.config.ProviderModelRegistry;
import com.javaclaw.server.extension.BuiltinIsolatedServices;

/** 从持久 Provider 与 Vault 凭据构造可热切换的模型和 Embedding 注册表。 */
final class ConfiguredProviderBootstrap {
    private ConfiguredProviderBootstrap() {}

    static AppServerBootstrap.Components create(
            AppServerBootstrap.Foundation foundation, ProviderCredentialResolver credentials) {
        Objects.requireNonNull(foundation, "foundation");
        ProviderCredentialResolver checkedCredentials = Objects.requireNonNull(credentials, "credentials");
        ProviderModelAdapterFactory modelAdapters = new ProviderModelAdapterFactory(checkedCredentials);
        ProviderEmbeddingAdapterFactory embeddingAdapters = new ProviderEmbeddingAdapterFactory(checkedCredentials);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            AppServerBootstrap.ownFoundation(startup, foundation);
            ProviderModelRegistry models = startup.own(new ProviderModelRegistry(
                    foundation.providers(),
                    modelAdapters::create,
                    foundation.vault().runtimeGate()));
            ProviderEmbeddingRegistry embeddings = startup.own(new ProviderEmbeddingRegistry(
                    foundation.providers(),
                    foundation.embeddingBinding(),
                    embeddingAdapters::create,
                    foundation.vault().runtimeGate()));
            foundation.vault().onRuntimeChange(models::invalidate, models::reload);
            foundation.vault().onRuntimeChange(embeddings::invalidate, embeddings::reload);
            return AppServerBootstrap.createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            models,
                            embeddings,
                            embeddingAdapters::create,
                            AppServerBootstrap.modelDiscovery(foundation, checkedCredentials),
                            BuiltinIsolatedServices.production(
                                    foundation.database(),
                                    foundation.attachments(),
                                    foundation.vault(),
                                    foundation.privateNetworkGrants(),
                                    foundation.json(),
                                    foundation.clock()),
                            AppServerBootstrap.productionMcpPorts(foundation)),
                    startup);
        }
    }
}
