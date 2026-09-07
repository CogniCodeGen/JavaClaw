package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.builtin.contracts.ScheduleContracts;

/**
 * 无人值守授权草稿使用的 Schedule、Role、权限和工具目录精确绑定。
 *
 * @param schedules 固定 Workspace 中的 Schedule 目录
 * @param schedule 当前精确定义
 * @param agentRole Schedule 引用的精确 Agent Role
 * @param permissionProfile Role 引用的精确 PermissionProfile
 * @param catalog 服务端权威可执行工具目录
 * @param tool 当前选择的精确工具
 */
public record UnattendedGrantBindingState(
        List<ScheduleContracts.Definition> schedules,
        Optional<ScheduleContracts.Definition> schedule,
        Optional<AgentRole> agentRole,
        Optional<PermissionProfile> permissionProfile,
        Optional<ToolCatalogQueryResult> catalog,
        Optional<ToolDescriptor> tool) {
    /** 复制目录并校验绑定层级。 */
    public UnattendedGrantBindingState {
        schedules = List.copyOf(Objects.requireNonNull(schedules, "schedules"));
        schedule = Objects.requireNonNull(schedule, "schedule");
        agentRole = Objects.requireNonNull(agentRole, "agentRole");
        permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
        catalog = Objects.requireNonNull(catalog, "catalog");
        tool = Objects.requireNonNull(tool, "tool");
        if (agentRole.isPresent() && schedule.isEmpty()
                || permissionProfile.isPresent() && agentRole.isEmpty()
                || catalog.isPresent() && permissionProfile.isEmpty()
                || tool.isPresent() && catalog.isEmpty()) {
            throw new IllegalArgumentException("无人值守授权目录绑定层级不完整");
        }
    }

    /** @return 空目录绑定 */
    public static UnattendedGrantBindingState empty() {
        return new UnattendedGrantBindingState(
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
