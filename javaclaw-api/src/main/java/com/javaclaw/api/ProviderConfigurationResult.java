package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 完整配置事务已提交的脱敏事实。
 *
 * @param provider 已提交的精确 Provider 版本
 * @param credential 最终绑定的凭据元数据；无绑定时为空
 */
public record ProviderConfigurationResult(ProviderEndpoint provider, Optional<CredentialMetadata> credential) {
    /** 拒绝空结果容器以及与 Provider 最终绑定不一致的凭据元数据。 */
    public ProviderConfigurationResult {
        Objects.requireNonNull(provider, "provider");
        credential = Objects.requireNonNull(credential, "credential");
        if (!provider.spec().credential().equals(credential.map(CredentialMetadata::reference))) {
            throw new IllegalArgumentException("configuration result credential does not match provider binding");
        }
    }
}
