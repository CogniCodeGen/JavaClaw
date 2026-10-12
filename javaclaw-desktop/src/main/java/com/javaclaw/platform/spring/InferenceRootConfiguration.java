package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceAssetIntegrityPort;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceManagementUseCase;
import com.javaclaw.application.inference.InferenceModelMetadataPort;
import com.javaclaw.application.inference.InferenceSecretPort;
import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.application.inference.InferenceSystemProfilePort;
import com.javaclaw.application.inference.LocalInferenceNetworkPort;
import com.javaclaw.application.inference.LocalInferenceQuickSetupApplicationService;
import com.javaclaw.application.inference.LocalInferenceQuickSetupUseCase;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.infrastructure.inference.*;
import com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 根进程拥有本地推理资产和服务插件控制面；推理实现只存在于插件进程。 */
@Configuration(proxyBeanMethods = false)
class InferenceRootConfiguration {

    @Bean
    InferenceCatalogPort inferenceCatalogPort(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            SchemaInitializer schemaInitializer) {
        return new JdbcInferenceCatalog(jdbc, transactionManager, json);
    }

    @Bean
    ManagedInferenceAssetStore inferenceAssetPreparationPort(DataRoot dataRoot,
            InferenceCatalogPort catalog, HttpClient http, ObjectMapper json) {
        return new ManagedInferenceAssetStore(dataRoot, catalog, http, json);
    }

    @Bean
    PluginModelCatalogAdapter pluginModelCatalogAdapter(PluginManagementService plugins,
            WorkspaceManager workspaces, InferenceCatalogPort catalog, ObjectMapper json) {
        return new PluginModelCatalogAdapter(plugins, workspaces, catalog, json);
    }

    @Bean(destroyMethod = "close")
    RemoteInferenceGateway remoteInferenceGateway(PluginManagementService plugins,
            WorkspaceManager workspaces, InferenceCatalogPort catalog,
            InferenceAssetIntegrityPort integrity, ObjectMapper json, CredentialCipher credentials, ManagedTaskExecutor tasks) {
        var gateway = new RemoteInferenceGateway(plugins, workspaces, catalog, integrity, json, credentials);
        gateway.init(tasks);
        return gateway;
    }

    @Bean(initMethod = "init", destroyMethod = "close")
    RemoteInferenceRuntimeCatalog remoteInferenceRuntimeCatalog(PluginManagementService plugins,
            WorkspaceManager workspaces, InferenceCatalogPort catalog, ObjectMapper json, ManagedTaskExecutor tasks) {
        return new RemoteInferenceRuntimeCatalog(plugins, workspaces, catalog, json, tasks);
    }

    @Bean
    InferenceSecretPort inferenceSecretPort(CredentialCipher credentials) {
        return new InferenceSecretPort() {
            @Override
            public String encrypt(String plainText) {
                return credentials.encrypt(plainText);
            }

            @Override
            public String decrypt(String encryptedText) {
                return credentials.decrypt(encryptedText);
            }
        };
    }

    @Bean
    InferenceManagementApplicationService inferenceManagementApplicationService(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceModelMetadataPort metadata,
            RemoteInferenceGateway runtimes,
            InferenceSecretPort secrets,
            RemoteInferenceGateway apiServer,
            HuggingFaceModelCatalogPort onlineModels,
            InferenceSystemProfilePort systemProfiles) {
        return new InferenceManagementUseCase(
                catalog, assets, metadata, runtimes, secrets, apiServer, onlineModels, systemProfiles);
    }

    @Bean
    LocalInferenceNetworkPort localInferenceNetworkPort() {
        return new JvmLocalInferenceNetworkAdapter();
    }

    @Bean
    LocalInferenceQuickSetupApplicationService localInferenceQuickSetupApplicationService(
            InferenceCatalogPort catalog,
            InferenceManagementApplicationService management,
            RemoteInferenceGateway runtimes,
            InferenceModelMetadataPort metadata,
            RemoteInferenceGateway apiServer,
            LocalInferenceNetworkPort network) {
        return new LocalInferenceQuickSetupUseCase(
                catalog, management, runtimes, metadata, apiServer, network);
    }

    @Bean(destroyMethod = "close")
    AutoCloseable inferenceApiUsageHostService(
            DefaultPluginManagementService plugins,
            InferenceCatalogPort catalog,
            ObjectMapper json) {
        return plugins.hostServices().register("inference/api-usage", Set.of("record"),
                "host.inference.api-usage", true, (request, cancelled, events) -> {
            if (cancelled.getAsBoolean() || Instant.now().isAfter(request.deadline()))
                throw new java.util.concurrent.CancellationException("API 用量回调已失效");
            var body = json.readTree(request.payloadJson());
            UUID keyId = UUID.fromString(body.path("keyId").asText());
            long minute = body.path("minute").asLong(-1);
            long currentMinute = Instant.now().getEpochSecond() / 60;
            if (minute < currentMinute - 5 || minute > currentMinute + 1) {
                throw new IllegalArgumentException("API 用量时间桶无效");
            }
            boolean activeKey = catalog.apiKeys().stream()
                    .anyMatch(key -> key.id().equals(keyId) && !key.revoked()
                            && catalog.publishedModels().stream().filter(model -> model.enabled()
                                && (key.modelAliases().isEmpty() || key.modelAliases().contains(model.alias())))
                            .anyMatch(model -> catalog.profile(model.profileId()).map(profile ->
                                profile.runtimeId().equals(request.pluginId())).orElse(false)));
            if (!activeKey) throw new SecurityException("API Key 已撤销或不存在");
            var usage = new com.javaclaw.inference.api.InferenceUsage(
                    body.path("promptTokens").asLong(-1),
                    body.path("completionTokens").asLong(-1));
            catalog.recordApiUsage(keyId, minute, usage, body.path("failed").asBoolean(false));
            return json.writeValueAsString(java.util.Map.of("recorded", true));
        });
    }
}
