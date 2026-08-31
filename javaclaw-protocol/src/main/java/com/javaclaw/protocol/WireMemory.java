package com.javaclaw.protocol;

import java.time.Instant;

/**
 * Workspace 范围内的版本化记忆记录。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param workspaceId 所属 Workspace 标识
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param content 正文内容，不应含明文凭据
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record WireMemory(
        String id,
        String workspaceId,
        String kind,
        String content,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
