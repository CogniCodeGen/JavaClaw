package com.javaclaw.server.turn;

import java.util.Objects;

import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;

/**
 * Turn 解析与调度所需的平台权威服务集合。
 *
 * @param core Core 读写用例
 * @param roles Agent Role 权威服务
 * @param configurations 安装、Workspace 与 Thread 独立执行默认值
 * @param permissions PermissionProfile 权威服务
 * @param instructions 项目约定解析器
 * @param worktrees Managed Worktree 权威服务
 */
public record TurnPlatformServices(
        CoreCommandService core,
        AgentRoleService roles,
        ExecutionConfigurationService configurations,
        PermissionProfileService permissions,
        ProjectInstructionResolver instructions,
        ManagedWorktreeService worktrees) {
    /** 校验所有平台服务均已由组合根提供。 */
    public TurnPlatformServices {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(configurations, "configurations");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(worktrees, "worktrees");
    }
}
