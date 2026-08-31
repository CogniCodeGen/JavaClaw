package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 可续传上传会话状态；receivedBytes 指示准确的续传位置。
 *
 * @param uploadId 可续传上传会话标识
 * @param expectedSha256 声明的内容摘要；未声明时为空字符串
 * @param mediaType 内容 MIME 类型
 * @param displayName 展示文件名；不作为客户端或服务器文件路径
 * @param expectedSize 上传声明总大小，单位字节
 * @param receivedBytes 服务端已接受的字节数，也是下一块期望 offset
 * @param chunkSize 单块最大解码字节数
 * @param expiresAt 上传或授权会话的过期时间
 */
public record AttachmentUploadInfo(
        String uploadId,
        String expectedSha256,
        String mediaType,
        String displayName,
        long expectedSize,
        long receivedBytes,
        int chunkSize,
        Instant expiresAt) {}
