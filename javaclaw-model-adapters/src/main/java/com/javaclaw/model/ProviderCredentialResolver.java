package com.javaclaw.model;

import java.util.Optional;

import com.javaclaw.api.CredentialRef;

/** Secret Vault 到模型 Adapter 的最小读取边界。 */
@FunctionalInterface
public interface ProviderCredentialResolver {
    /**
     * 解析短生命周期凭据材料；调用方负责关闭并清零。
     *
     * @param reference opaque Vault 引用
     * @return Vault 可用时的凭据材料
     */
    Optional<CredentialMaterial> resolve(CredentialRef reference);
}
