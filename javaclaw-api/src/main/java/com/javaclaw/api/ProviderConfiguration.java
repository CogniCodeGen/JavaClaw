package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次原子提交的完整 Provider 业务配置；不含秘密值或任意 CredentialRef。
 *
 * @param providerId Provider 稳定标识
 * @param expectedRevision Provider 期望版本；新建为 0
 * @param connection 连接配置
 * @param models 至少一个明确用途的模型
 * @param lifecycle 最终启停状态；不允许归档
 * @param credentialChange 凭据保留、替换或清除意图
 * @param credentialExpectedRevision 当前凭据版本；未绑定时为 0
 */
public record ProviderConfiguration(
        String providerId,
        long expectedRevision,
        ProviderConnectionSpec connection,
        List<ProviderModelSpec> models,
        ProviderLifecycle lifecycle,
        ProviderCredentialChange credentialChange,
        long credentialExpectedRevision) {
    /** 复制模型并沿用端点校验；版本冲突与凭据归属由服务端校验。 */
    public ProviderConfiguration {
        providerId = Preconditions.identifier(providerId, "providerId");
        if (expectedRevision < 0 || credentialExpectedRevision < 0) {
            throw new IllegalArgumentException("expected revisions must not be negative");
        }
        Objects.requireNonNull(connection, "connection");
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        if (models.isEmpty()) {
            throw new IllegalArgumentException("configuration must select at least one model");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (lifecycle == ProviderLifecycle.ARCHIVED) {
            throw new IllegalArgumentException("configuration cannot archive a provider");
        }
        Objects.requireNonNull(credentialChange, "credentialChange");
        connection.toEndpointSpec(models, Optional.empty());
    }
}
