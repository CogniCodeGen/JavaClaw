package com.javaclaw.runtime;

import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/**
 * 模型图片的可信附件投影；只持久化引用，不把图片字节或可联网 URL 写入 Provider state。
 *
 * @param attachment 内容寻址图片及原始字节数
 * @param workspaceId 附件所属 Workspace
 * @param threadId 已核验引用该图片的 Thread
 * @param observationId 观察身份，用于区分内容相同但时刻不同的图片
 * @param width 图片像素宽度，范围 1–8192
 * @param height 图片像素高度，范围 1–8192
 */
public record ModelImage(
        AttachmentRef attachment,
        WorkspaceId workspaceId,
        ThreadId threadId,
        String observationId,
        int width,
        int height) {
    /** 每张图片最多 4 MiB；解码像素另有限制，避免小文件触发巨量展开。 */
    public static final long MAXIMUM_BYTES = 4L * 1024 * 1024;

    /** 校验传输格式、身份和图片预算；实际读取还必须复核附件所有权。 */
    public ModelImage {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        observationId = Objects.requireNonNull(observationId, "observationId").strip();
        if (observationId.isEmpty() || observationId.length() > 200) {
            throw new IllegalArgumentException("图片必须关联有界观察身份");
        }
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(attachment.mediaType())
                || attachment.sizeBytes() < 1
                || attachment.sizeBytes() > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("图片格式或字节预算不受支持");
        }
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_777_216) {
            throw new IllegalArgumentException("图片像素预算超限");
        }
    }
}
