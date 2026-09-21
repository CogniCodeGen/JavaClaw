package com.javaclaw.api;

/**
 * 草稿编辑来源的双版本身份；由服务端解析已有凭据。
 *
 * @param providerId 已保存 Provider 标识
 * @param providerRevision 已保存精确版本，必须为正数
 * @param credentialRevision 当前凭据版本；未绑定时为 0
 */
public record ProviderConfigurationSource(String providerId, long providerRevision, long credentialRevision) {
    /** 校验来源版本，不允许客户端传入 Vault 引用。 */
    public ProviderConfigurationSource {
        providerId = Preconditions.identifier(providerId, "providerId");
        providerRevision = Preconditions.positive(providerRevision, "providerRevision");
        if (credentialRevision < 0) {
            throw new IllegalArgumentException("credentialRevision must not be negative");
        }
    }
}
