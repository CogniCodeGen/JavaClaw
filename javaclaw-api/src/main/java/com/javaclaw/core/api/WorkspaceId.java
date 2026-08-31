package com.javaclaw.core.api;

/**
 * Stable local workspace identity.
 *
 * @param value 去除首尾空白后的非空标识
 */
public record WorkspaceId(String value) {
    /** 去除标识首尾空白；null 或空白标识不合法。 */
    public WorkspaceId {
        value = ThreadId.required(value, "workspaceId");
    }

    @Override
    public String toString() {
        return value;
    }
}
