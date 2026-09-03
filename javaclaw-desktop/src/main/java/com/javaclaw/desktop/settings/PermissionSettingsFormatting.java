package com.javaclaw.desktop.settings;

import java.util.Objects;

import com.javaclaw.api.PermissionSection;

/** PermissionProfile 表单的无副作用文本转换。 */
final class PermissionSettingsFormatting {
    private PermissionSettingsFormatting() {}

    /** @return 无法解析时为 -1 的长整数 */
    static long longValue(String value) {
        try {
            return Long.parseLong(Objects.requireNonNullElse(value, "").strip());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** @return 无法解析或越界时为 -1 的整数 */
    static int intValue(String value) {
        long parsed = longValue(value);
        return parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE ? -1 : (int) parsed;
    }

    /** @return 权限分区的用户可读中文名 */
    static String section(PermissionSection section) {
        return switch (Objects.requireNonNull(section, "section")) {
            case FILE -> "文件";
            case NETWORK -> "网络";
            case PROCESS -> "进程与交互终端";
            case TOOL -> "工具与审批";
            case RESOURCE -> "资源上限";
        };
    }
}
