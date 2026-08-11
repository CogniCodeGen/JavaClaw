package com.javaclaw.application.tool;

/** 工具授权的显式决定。 */
public record ToolAuthorization(boolean allowed, String reason) {

    public ToolAuthorization {
        reason = reason == null ? "" : reason;
    }

    public static ToolAuthorization allow() {
        return new ToolAuthorization(true, "");
    }

    public static ToolAuthorization reject(String reason) {
        return new ToolAuthorization(false, reason);
    }
}
