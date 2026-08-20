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
import com.javaclaw.infrastructure.inference.DeliveranceRuntimeManager;
import com.javaclaw.infrastructure.inference.JdbcInferenceCatalog;
import com.javaclaw.infrastructure.inference.JvmLocalInferenceNetworkAdapter;
import com.javaclaw.infrastructure.inference.ManagedInferenceAssetStore;
import com.javaclaw.infrastructure.inference.HuggingFaceModelCatalogClient;
import com.javaclaw.infrastructure.inference.JvmInferenceSystemProfileAdapter;
import com.javaclaw.infrastructure.inference.serviceplugin.DeliveranceServicePluginGateway;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
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
    ManagedInferenceAssetStore inferenceAssetPreparationPort(
            DataRoot dataRoot,
            InferenceCatalogPort catalog,
            HttpClient http,
            ObjectMapper json,
            ServicePluginProcessManager servicePlugins) {
        return new ManagedInferenceAssetStore(dataRoot, () -> servicePlugins
                .definition(DeliveranceServicePluginGateway.PLUGIN_ID)
                .map(value -> value.dataDirectory())
                .orElseThrow(() -> new IllegalStateException(
                        "Deliverance 插件尚未注册，无法解析唯一 data 目录")),
                catalog, http, json);
    }

    @Bean
    HuggingFaceModelCatalogPort huggingFaceModelCatalogPort(HttpClient http, ObjectMapper json) {
        return new HuggingFaceModelCatalogClient(http, json);
    }

    @Bean
    InferenceSystemProfilePort inferenceSystemProfilePort() {
        return new JvmInferenceSystemProfileAdapter();
    }

    @Bean
    DeliveranceServicePluginGateway deliveranceServicePluginGateway(
            InferenceCatalogPort catalog,
            InferenceAssetIntegrityPort assetIntegrity,
            ServicePluginProcessManager servicePlugins,
            ObjectMapper json,
            CredentialCipher credentials) {
        return new DeliveranceServicePluginGateway(
                catalog, assetIntegrity, servicePlugins, json, credentials);
    }

    @Bean(initMethod = "init")
    DeliveranceRuntimeManager deliveranceRuntimeManager(
            DataRoot dataRoot,
            InferenceCatalogPort catalog,
            DeliveranceServicePluginGateway gateway,
            ManagedTaskExecutor tasks,
            ObjectMapper json) {
        return new DeliveranceRuntimeManager(dataRoot, catalog, gateway, tasks, json);
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
            DeliveranceRuntimeManager runtimes,
            InferenceSecretPort secrets,
            DeliveranceServicePluginGateway apiServer,
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
            DeliveranceRuntimeManager runtimes,
            InferenceModelMetadataPort metadata,
            DeliveranceServicePluginGateway apiServer,
            LocalInferenceNetworkPort network) {
        return new LocalInferenceQuickSetupUseCase(
                catalog, management, runtimes, metadata, apiServer, network);
    }

    @Bean(destroyMethod = "close")
    AutoCloseable inferenceApiUsageHostService(
            com.javaclaw.infrastructure.serviceplugin.ServicePluginHostServiceRegistry services,
            InferenceCatalogPort catalog,
            ObjectMapper json) {
        return services.register("inference/api-usage", Set.of("record"),
                "inference.api-usage", (request, cancelled, events) -> {
            if (!DeliveranceServicePluginGateway.PLUGIN_ID.equals(request.pluginId())) {
                throw new SecurityException("只有内置 Deliverance 插件可以登记推理 API 用量");
            }
            var body = json.readTree(request.payload());
            UUID keyId = UUID.fromString(body.path("keyId").asText());
            long minute = body.path("minute").asLong(-1);
            long currentMinute = Instant.now().getEpochSecond() / 60;
            if (minute < currentMinute - 5 || minute > currentMinute + 1) {
                throw new IllegalArgumentException("API 用量时间桶无效");
            }
            boolean activeKey = catalog.apiKeys().stream()
                    .anyMatch(key -> key.id().equals(keyId) && !key.revoked());
            if (!activeKey) throw new SecurityException("API Key 已撤销或不存在");
            var usage = new com.javaclaw.inference.api.InferenceUsage(
                    body.path("promptTokens").asLong(-1),
                    body.path("completionTokens").asLong(-1));
            catalog.recordApiUsage(keyId, minute, usage, body.path("failed").asBoolean(false));
            return new com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter.Response(
                    "application/json", json.writeValueAsBytes(java.util.Map.of("recorded", true)));
        });
    }
}
