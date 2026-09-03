package com.javaclaw.api;

/**
 * 由应用代码维护的只读 PermissionProfile 预设描述。
 *
 * @param id 稳定预设标识
 * @param revision 预设版本，从 1 开始
 * @param displayName 用户可见名称
 * @param description 简短权限边界说明
 * @param executableSelectionAllowed 是否允许实例化时选择精确 executable
 */
public record PermissionPresetDescriptor(
        String id, long revision, String displayName, String description, boolean executableSelectionAllowed) {
    /** 校验预设身份和说明。 */
    public PermissionPresetDescriptor {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        displayName = Preconditions.boundedText(displayName, "displayName", 1_000);
        description = Preconditions.boundedText(description, "description", 4_000);
    }
}
