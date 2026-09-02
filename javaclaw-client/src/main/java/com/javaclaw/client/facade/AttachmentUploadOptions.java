package com.javaclaw.client.facade;

import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.AttachmentRpcContracts;

/**
 * 本地文件上传为 Core Attachment 的有界选项。
 *
 * @param scope 上传完成后写入的显式所有权范围
 * @param mediaType 已由本地客户端判定的 MIME 类型
 * @param maximumBytes 本次调用允许读取的最大原始字节数
 * @param cancellation 协作式取消信号
 * @param command 幂等键；Attachment 不使用资源 revision，因此 expected revision 必须为 0
 */
public record AttachmentUploadOptions(
        AttachmentScope scope,
        String mediaType,
        long maximumBytes,
        CancellationToken cancellation,
        CommandOptions command) {
    /** 校验本地读取和服务端提交边界。 */
    public AttachmentUploadOptions {
        Objects.requireNonNull(scope, "scope");
        mediaType = Objects.requireNonNull(mediaType, "mediaType").strip().toLowerCase(Locale.ROOT);
        if (mediaType.length() > 240 || !mediaType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("mediaType must be a simple MIME type");
        }
        if (maximumBytes < 1 || maximumBytes > AttachmentRpcContracts.MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("maximumBytes exceeds the Core Attachment limit");
        }
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(command, "command");
        if (command.expectedRevision() != 0) {
            throw new IllegalArgumentException("Attachment upload expected revision must be 0");
        }
    }
}
