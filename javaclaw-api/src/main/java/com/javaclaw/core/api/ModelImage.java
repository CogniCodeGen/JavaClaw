package com.javaclaw.core.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** 内容寻址的瞬时图片输入；仅在模型请求期间持有 bytes，不写入事件、诊断或客户端配置。 */
public final class ModelImage {
    public static final int MAXIMUM_BYTES = 8 * 1024 * 1024;
    private final String sha256;
    private final String mediaType;
    private final byte[] bytes;

    /** 校验 SHA-256、受支持 MIME 与 8 MiB 上限并复制字节；头部和尺寸由附件解析边界验证。 */
    public ModelImage(String sha256, String mediaType, byte[] bytes) {
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(bytes, "bytes");
        if (!Set.of("image/png", "image/jpeg").contains(mediaType)
                || bytes.length < 1
                || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("image MIME or size is unsupported");
        }
        this.bytes = bytes.clone();
        try {
            if (!sha256.equals(HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes)))) {
                throw new IllegalArgumentException("image content hash mismatch");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 返回已验证的内容地址，供快照引用而非持久化图片正文。 */
    public String sha256() {
        return sha256;
    }

    /** 返回已验证的 image/png 或 image/jpeg 类型。 */
    public String mediaType() {
        return mediaType;
    }

    /** 返回字节副本，Provider 不能修改后续重试使用的输入。 */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** 返回编码图片的字节数，不分配额外副本。 */
    public int sizeBytes() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return "ModelImage[sha256=" + sha256 + ", mediaType=" + mediaType + ", sizeBytes=" + bytes.length + "]";
    }
}
