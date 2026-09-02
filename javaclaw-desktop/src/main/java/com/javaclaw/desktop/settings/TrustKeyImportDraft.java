package com.javaclaw.desktop.settings;

import java.util.Objects;

import com.javaclaw.api.AttachmentMetadata;

/**
 * Trust Key 导入前的指纹确认草稿，不持有公钥原始字节或宿主绝对路径。
 *
 * @param fileName 用户选择的文件名
 * @param attachment 已上传的内容寻址 Attachment
 * @param fingerprint 规范 DER 公钥 SHA-256 指纹
 */
public record TrustKeyImportDraft(String fileName, AttachmentMetadata attachment, String fingerprint) {
    /** 校验文件名、Attachment 和指纹。 */
    public TrustKeyImportDraft {
        fileName = Objects.requireNonNull(fileName, "fileName").strip();
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        Objects.requireNonNull(attachment, "attachment");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint").toLowerCase(java.util.Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint 必须为 SHA-256");
        }
    }
}
