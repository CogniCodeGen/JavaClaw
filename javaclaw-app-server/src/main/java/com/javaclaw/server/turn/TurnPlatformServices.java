package com.javaclaw.server.turn;

import java.util.Objects;

import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;

/**
 * Turn 解析与调度所需的平台权威服务集合。
 *
 * @param core Core 读写用例
 * @param profiles Agent Profile 权威服务
 * @param bindings Workspace 与 Thread Profile 绑定
 * @param permissions PermissionProfile 权威服务
 * @param instructions 项目约定解析器
 * @param worktrees Managed Worktree 权威服务
 */
public record TurnPlatformServices(
        CoreCommandService core,
        AgentProfileService profiles,
        ProfileBindingService bindings,
        PermissionProfileService permissions,
        ProjectInstructionResolver instructions,
        ManagedWorktreeService worktrees) {
    /** 校验所有平台服务均已由组合根提供。 */
    public TurnPlatformServices {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(worktrees, "worktrees");
    }
}
