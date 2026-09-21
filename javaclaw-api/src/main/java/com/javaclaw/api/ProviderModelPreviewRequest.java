package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 未保存连接的模型目录预览请求；代次用于隔离迟到结果。
 *
 * @param draftId 当前弹窗的随机草稿标识
 * @param generation 正数草稿代次；连接或凭据变化时递增
 * @param connection 非敏感连接草稿
 * @param source 可选的已保存编辑来源
 * @param credentialChange 本次读取的凭据意图；CLEAR 仅表示无鉴权，不执行写入
 */
public record ProviderModelPreviewRequest(
        String draftId,
        long generation,
        ProviderConnectionSpec connection,
        Optional<ProviderConfigurationSource> source,
        ProviderCredentialChange credentialChange) {
    /** 校验草稿身份和非空容器；凭据归属由服务端校验。 */
    public ProviderModelPreviewRequest {
        draftId = Preconditions.identifier(draftId, "draftId");
        generation = Preconditions.positive(generation, "generation");
        Objects.requireNonNull(connection, "connection");
        source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(credentialChange, "credentialChange");
    }
}
