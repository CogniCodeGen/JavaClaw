package com.javaclaw.api;

import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Resource 的外部内容；正文或 Blob 必须且只能提供一个。
 *
 * @param uri 内容来源 URI
 * @param mimeType 可选 MIME 类型
 * @param text 可选 UTF-16 Java 文本
 * @param blobBase64 可选 Base64 Blob，调用方必须显式处理
 */
public record McpResourceContent(
        String uri, Optional<String> mimeType, Optional<String> text, Optional<String> blobBase64) {
    private static final int MAXIMUM_ENCODED_CONTENT = 6 * 1024 * 1024;

    /** 复制并校验内容。 */
    public McpResourceContent {
        uri = McpResourceDescriptor.resourceUri(uri);
        mimeType = McpResourceDescriptor.optionalText(mimeType, "mimeType", 200);
        text = Objects.requireNonNull(text, "text").map(McpResourceContent::content);
        blobBase64 = Objects.requireNonNull(blobBase64, "blobBase64").map(McpResourceContent::base64);
        if (text.isPresent() == blobBase64.isPresent()) {
            throw new IllegalArgumentException("resource content requires exactly one text or blob");
        }
    }

    private static String content(String value) {
        String checked = Objects.requireNonNull(value, "text");
        if (checked.length() > MAXIMUM_ENCODED_CONTENT) {
            throw new IllegalArgumentException("resource text exceeds the content limit");
        }
        return checked;
    }

    private static String base64(String value) {
        String checked = content(value);
        try {
            Base64.getDecoder().decode(checked);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("resource blob is not valid Base64", failure);
        }
        return checked;
    }
}
