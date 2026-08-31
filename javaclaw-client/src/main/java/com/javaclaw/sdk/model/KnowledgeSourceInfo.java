package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 知识源元数据与索引状态，包括关键词模式和 Embedding 降级状态。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param workspaceId 所属 Workspace 标识
 * @param attachmentSha256 原始文档附件内容摘要
 * @param displayName 展示文件名；不作为客户端或服务器文件路径
 * @param mediaType 内容 MIME 类型
 * @param status 服务端生命周期或执行结果状态
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record KnowledgeSourceInfo(
        String id,
        String workspaceId,
        String attachmentSha256,
        String displayName,
        String mediaType,
        String status,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
