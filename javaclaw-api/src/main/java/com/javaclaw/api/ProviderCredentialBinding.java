package com.javaclaw.api;

import java.util.Objects;

/**
 * Provider 与 Vault 凭据完成原子绑定或轮换后的脱敏结果。
 *
 * @param provider 已提交的新 Provider 版本
 * @param credential 已提交的凭据元数据，不包含 Secret
 */
public record ProviderCredentialBinding(ProviderEndpoint provider, CredentialMetadata credential) {
    /** 校验 Provider 与凭据引用保持一致。 */
    public ProviderCredentialBinding {
        provider = Objects.requireNonNull(provider, "provider");
        credential = Objects.requireNonNull(credential, "credential");
        CredentialRef bound = provider.spec()
                .credential()
                .orElseThrow(() -> new IllegalArgumentException("provider must reference the credential"));
        if (!bound.equals(credential.reference())) {
            throw new IllegalArgumentException("provider credential reference does not match metadata");
        }
    }
}
