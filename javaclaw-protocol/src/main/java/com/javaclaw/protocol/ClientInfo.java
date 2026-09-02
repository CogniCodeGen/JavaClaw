package com.javaclaw.protocol;

import java.util.Objects;

/**
 * 客户端身份信息。
 *
 * @param name 客户端名称
 * @param version 客户端版本
 */
public record ClientInfo(String name, String version) {
    /** 校验客户端信息。 */
    public ClientInfo {
        name = text(name, "name");
        version = text(version, "version");
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
