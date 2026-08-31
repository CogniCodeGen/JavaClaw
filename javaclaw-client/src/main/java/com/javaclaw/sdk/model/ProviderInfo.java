package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 云模型配置与凭据存在性视图，不返回 API Key。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param configured 是否存在有效凭据配置；不暴露凭据值
 * @param credentialRevision 凭据修订号，未配置时为 0
 * @param configRevision Provider 配置修订号
 * @param model 对话模型标识
 * @param embeddingModel Embedding 模型标识；未配置时可为空
 * @param baseUrl Provider 基础 URL；未覆盖默认地址时可为空
 * @param updatedAt 配置或凭据最近更新时间；尚未配置时可为 null
 */
public record ProviderInfo(
        String id,
        boolean configured,
        long credentialRevision,
        long configRevision,
        String model,
        String embeddingModel,
        String baseUrl,
        Instant updatedAt) {}
