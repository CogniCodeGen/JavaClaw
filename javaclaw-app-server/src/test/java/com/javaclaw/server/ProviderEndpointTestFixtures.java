package com.javaclaw.server;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;

/** 为 App Server 测试创建不含 Secret 的强类型 Provider 配置。 */
public final class ProviderEndpointTestFixtures {
    private ProviderEndpointTestFixtures() {}

    /**
     * 创建仅用于对话的 Provider 配置。
     *
     * @param displayName 用户可见名称
     * @param adapter Provider Adapter
     * @param modelIds 一个或多个模型标识
     * @return 使用默认高级选项的配置
     */
    public static ProviderEndpointSpec chat(String displayName, ProviderAdapter adapter, String... modelIds) {
        return create(displayName, adapter, ProviderModelPurpose.CHAT, modelIds);
    }

    /**
     * 创建仅用于向量化的 Provider 配置。
     *
     * @param displayName 用户可见名称
     * @param adapter Provider Adapter
     * @param modelIds 一个或多个模型标识
     * @return 使用默认高级选项的配置
     */
    public static ProviderEndpointSpec embedding(String displayName, ProviderAdapter adapter, String... modelIds) {
        return create(displayName, adapter, ProviderModelPurpose.EMBEDDING, modelIds);
    }

    /**
     * 创建需要先处于 DISABLED、再经凭据服务绑定 Secret 的 API Key 对话配置。
     *
     * @param displayName 用户可见名称
     * @param adapter Provider Adapter
     * @param modelIds 一个或多个模型标识
     * @return 尚未绑定 CredentialRef 的配置壳
     */
    public static ProviderEndpointSpec apiKeyChat(String displayName, ProviderAdapter adapter, String... modelIds) {
        return create(displayName, adapter, ProviderModelPurpose.CHAT, ProviderAuthentication.API_KEY, modelIds);
    }

    private static ProviderEndpointSpec create(
            String displayName, ProviderAdapter adapter, ProviderModelPurpose purpose, String... modelIds) {
        ProviderAuthentication authentication = adapter == ProviderAdapter.OPENAI_COMPATIBLE
                ? ProviderAuthentication.NONE
                : ProviderAuthentication.API_KEY;
        return create(displayName, adapter, purpose, authentication, modelIds);
    }

    private static ProviderEndpointSpec create(
            String displayName,
            ProviderAdapter adapter,
            ProviderModelPurpose purpose,
            ProviderAuthentication authentication,
            String... modelIds) {
        List<ProviderModelSpec> models = Arrays.stream(modelIds)
                .map(modelId -> new ProviderModelSpec(modelId, modelId, Set.of(purpose), OptionalInt.empty()))
                .toList();
        return new ProviderEndpointSpec(
                displayName,
                adapter,
                authentication == ProviderAuthentication.NONE
                        ? Optional.of(URI.create("http://127.0.0.1:11434/v1"))
                        : Optional.empty(),
                authentication,
                models,
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(adapter));
    }
}
