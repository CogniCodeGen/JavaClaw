package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 不含模型目录或 Vault 引用的 Provider 连接草稿。
 *
 * @param displayName 用户可见名称
 * @param adapter 协议适配器
 * @param baseUri 可选 API 根地址；为空时沿用适配器默认值
 * @param authentication 鉴权方式
 * @param timeout 单次调用超时
 * @param maximumRetries 最大重试次数
 * @param options 适配器非敏感参数
 */
public record ProviderConnectionSpec(
        String displayName,
        ProviderAdapter adapter,
        Optional<URI> baseUri,
        ProviderAuthentication authentication,
        Duration timeout,
        int maximumRetries,
        ProviderAdapterOptions options) {
    /** 沿用已保存端点的地址、协议和请求参数约束。 */
    public ProviderConnectionSpec {
        ProviderEndpointSpec checked = new ProviderEndpointSpec(
                displayName,
                adapter,
                baseUri,
                authentication,
                List.of(),
                Optional.empty(),
                timeout,
                maximumRetries,
                options);
        displayName = checked.displayName();
    }

    /**
     * 从已保存端点复制非敏感连接信息。
     *
     * @param spec 已保存配置
     * @return 不携带凭据引用或模型的连接草稿
     */
    public static ProviderConnectionSpec from(ProviderEndpointSpec spec) {
        return new ProviderConnectionSpec(
                spec.displayName(),
                spec.adapter(),
                spec.baseUri(),
                spec.authentication(),
                spec.timeout(),
                spec.maximumRetries(),
                spec.options());
    }

    /**
     * 在服务端已解析凭据后组成完整端点配置。
     *
     * @param models 用户明确选择的模型目录
     * @param credential 服务端解析的可选 Vault 引用
     * @return 沿用既有校验的端点配置
     */
    public ProviderEndpointSpec toEndpointSpec(List<ProviderModelSpec> models, Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                displayName, adapter, baseUri, authentication, models, credential, timeout, maximumRetries, options);
    }
}
