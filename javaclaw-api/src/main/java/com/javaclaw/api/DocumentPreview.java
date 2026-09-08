package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一个连接独占的不可变预览版本；句柄和摘要都不授予读取权限。
 *
 * @param handleId 不可猜测的会话句柄 UUID
 * @param workspaceId 所属 Workspace
 * @param fileName 用户可见文件名，不包含宿主绝对路径
 * @param mediaType 内容类型；客户端仍需检查实际图片格式
 * @param sizeBytes 内容字节数，0 至 64 MiB
 * @param digest 内容 SHA-256
 * @param origin 当前文件或已持久化引用版本
 * @param expiresAt 闲置租约到期时间
 * @param startLine 可选的一起始定位行号
 * @param changed 当前文件与原引用摘要是否不同
 */
public record DocumentPreview(
        String handleId,
        WorkspaceId workspaceId,
        String fileName,
        String mediaType,
        long sizeBytes,
        String digest,
        Origin origin,
        Instant expiresAt,
        Optional<Integer> startLine,
        boolean changed) {
    /** 显示版本的真实来源，不能把当前文件冒充历史版本。 */
    public enum Origin {
        /** 创建预览时读取的工作区文件。 */
        CURRENT_FILE,
        /** 已提交消息或内容寻址附件。 */
        REFERENCED_VERSION
    }

    /** 校验句柄、内容大小和摘要。 */
    public DocumentPreview {
        handleId = UUID.fromString(handleId).toString();
        Objects.requireNonNull(workspaceId, "workspaceId");
        fileName = Preconditions.text(fileName, "fileName");
        mediaType = Preconditions.text(mediaType, "mediaType");
        if (sizeBytes < 0 || sizeBytes > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("预览源超过 64 MiB");
        }
        digest = Preconditions.digest(digest, "digest");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(expiresAt, "expiresAt");
        startLine = Objects.requireNonNull(startLine, "startLine");
        if (startLine.filter(value -> value < 1).isPresent()) {
            throw new IllegalArgumentException("行号必须从 1 开始");
        }
    }
}
