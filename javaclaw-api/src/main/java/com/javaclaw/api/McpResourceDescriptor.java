package com.javaclaw.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Endpoint 声明的外部 Resource；该描述不会自动进入 Prompt 或 system context。
 *
 * @param name 资源名称
 * @param uri 由远端解释的绝对 URI
 * @param title 可选展示标题
 * @param description 可选说明
 * @param mimeType 可选 MIME 类型
 * @param size 可选字节数
 */
public record McpResourceDescriptor(
        String name,
        String uri,
        Optional<String> title,
        Optional<String> description,
        Optional<String> mimeType,
        Optional<Long> size) {
    /** 复制并校验描述。 */
    public McpResourceDescriptor {
        name = boundedText(name, "name", 240);
        uri = resourceUri(uri);
        title = optionalText(title, "title", 500);
        description = optionalText(description, "description", 4_000);
        mimeType = optionalText(mimeType, "mimeType", 200);
        size = Objects.requireNonNull(size, "size").map(value -> Preconditions.nonNegative(value, "size"));
    }

    static String resourceUri(String value) {
        String checked = boundedText(value, "uri", 4_096);
        URI parsed;
        try {
            parsed = URI.create(checked);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("uri is invalid", failure);
        }
        if (!parsed.isAbsolute() || parsed.getScheme() == null) {
            throw new IllegalArgumentException("uri must be absolute");
        }
        return checked;
    }

    static Optional<String> optionalText(Optional<String> value, String name, int maximum) {
        return Objects.requireNonNull(value, name).map(text -> boundedText(text, name, maximum));
    }

    static String boundedText(String value, String name, int maximum) {
        String checked = Preconditions.text(value, name);
        if (checked.length() > maximum || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }
}
