package com.javaclaw.server.model;

import java.time.Instant;

/**
 * Non-secret provider state owned by the server domain, not the wire protocol.
 *
 * @param id 资源或声明的稳定标识
 * @param configured 是否已配置可用凭据；不包含凭据值
 * @param credentialRevision 凭据修订号；未配置时为 0
 * @param configRevision Provider 配置修订号
 * @param model 对话模型标识
 * @param embeddingModel Embedding 模型；未配置时可为空
 * @param baseUrl Provider 基础 URL；未覆盖默认值时可为空
 * @param updatedAt 最近更新时间；尚未配置的资源可为 null
 */
public record ProviderState(
        String id,
        boolean configured,
        long credentialRevision,
        long configRevision,
        String model,
        String embeddingModel,
        String baseUrl,
        Instant updatedAt) {}
