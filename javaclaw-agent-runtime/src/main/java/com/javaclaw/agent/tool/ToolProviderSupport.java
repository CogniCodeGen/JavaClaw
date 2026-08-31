package com.javaclaw.agent.tool;

final class ToolProviderSupport {
    private ToolProviderSupport() {}

    static String requireId(String value) {
        String id = value == null ? "" : value.strip();
        if (!id.matches("[A-Za-z][A-Za-z0-9._-]{0,159}")) {
            throw new IllegalArgumentException("invalid tool provider id: " + value);
        }
        return id;
    }
}
