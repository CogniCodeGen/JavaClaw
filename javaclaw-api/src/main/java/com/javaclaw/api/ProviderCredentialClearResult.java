package com.javaclaw.api;

import java.util.Objects;

/**
 * Provider 解除引用并永久清除 Vault 凭据后的原子结果。
 *
 * @param provider 已提交且不再携带凭据引用的新 Provider 版本
 * @param receipt Vault 清除回执
 */
public record ProviderCredentialClearResult(ProviderEndpoint provider, CredentialClearReceipt receipt) {
    /** 校验 Provider 已解除引用。 */
    public ProviderCredentialClearResult {
        provider = Objects.requireNonNull(provider, "provider");
        receipt = Objects.requireNonNull(receipt, "receipt");
        if (provider.spec().credential().isPresent()) {
            throw new IllegalArgumentException("provider must not retain a cleared credential reference");
        }
    }
}
