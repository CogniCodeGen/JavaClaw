package com.javaclaw.server.turn;

import java.util.Objects;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolRisk;

/** 设置界面发现工具时使用的能力上限判定，不授予任何执行权限。 */
final class ToolSelectionPolicy {
    private ToolSelectionPolicy() {}

    /**
     * 判断工具是否可以作为当前权限预设的候选项展示。
     *
     * <p>工具名称白名单在用户选择后才写入，因此本判定只检查风险上限以及文件、网络和进程基础能力。执行路径仍进行完整的精确名称校验。
     *
     * @param descriptor 工具声明
     * @param permissions 权限能力上限
     * @return 可以展示为候选项时为 true
     */
    static boolean allows(ToolDescriptor descriptor, PermissionProfile permissions) {
        ToolRisk risk = Objects.requireNonNull(descriptor, "descriptor").risk();
        PermissionProfile checked = Objects.requireNonNull(permissions, "permissions");
        if (risk.ordinal() > checked.tools().maximumRisk().ordinal()) {
            return false;
        }
        return switch (risk) {
            case READ_ONLY -> true;
            case WORKSPACE_WRITE -> !checked.files().writeRoots().isEmpty();
            case NETWORK ->
                !checked.network().hosts().isEmpty()
                        && !checked.network().ports().isEmpty();
            case PROCESS -> !checked.processes().executables().isEmpty();
            case EXTERNAL_EFFECT -> true;
        };
    }
}
