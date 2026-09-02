package com.javaclaw.api;

/**
 * 冻结工具目录中的稳定身份。
 *
 * @param producerId Core 或 Extension 标识
 * @param name 全局唯一工具名
 * @param revision 单调递增版本，从 1 开始
 */
public record ToolIdentity(String producerId, String name, long revision) {
    /** 校验来源、名称和版本。 */
    public ToolIdentity {
        producerId = Preconditions.text(producerId, "producerId");
        name = Preconditions.text(name, "name");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("tool name must be provider-safe ASCII");
        }
        revision = Preconditions.positive(revision, "revision");
    }
}
