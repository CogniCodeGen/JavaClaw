package com.javaclaw.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import com.javaclaw.runtime.ModelImage;
import com.javaclaw.runtime.ModelImageResolver;

/** 图片只在模型请求边界读取；附件身份与读取字节不一致时拒绝调用。 */
final class ModelImageTransport {
    private final ModelImageResolver resolver;

    ModelImageTransport(ModelImageResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    byte[] bytes(ModelImage image) {
        byte[] bytes = resolver.resolve(image);
        if (bytes == null
                || bytes.length != image.attachment().sizeBytes()
                || bytes.length > ModelImage.MAXIMUM_BYTES
                || !digest(bytes).equals(image.attachment().digest())) {
            throw new IllegalStateException("图片附件内容与可信引用不匹配");
        }
        return bytes;
    }

    String dataUrl(ModelImage image) {
        return "data:" + image.attachment().mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(bytes(image));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
