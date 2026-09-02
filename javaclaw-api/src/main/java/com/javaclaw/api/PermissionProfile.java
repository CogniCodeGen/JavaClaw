package com.javaclaw.api;

/**
 * 版本化权限配置；执行权限只能通过多个配置求交得到，不能求并集。
 *
 * @param id 稳定配置标识
 * @param version 单调递增版本，从 1 开始
 * @param files 文件权限
 * @param network 网络权限
 * @param processes 进程与 PTY 权限
 * @param tools 工具风险与审批权限
 * @param resources 资源上限
 */
public record PermissionProfile(
        String id,
        long version,
        FilePermission files,
        NetworkPermission network,
        ProcessPermission processes,
        ToolPermission tools,
        ResourceLimits resources) {
    /** 校验配置标识、版本和所有子策略。 */
    public PermissionProfile {
        id = Preconditions.text(id, "id");
        version = Preconditions.positive(version, "version");
        if (files == null || network == null || processes == null || tools == null || resources == null) {
            throw new IllegalArgumentException("permission profile sections are required");
        }
    }
}
