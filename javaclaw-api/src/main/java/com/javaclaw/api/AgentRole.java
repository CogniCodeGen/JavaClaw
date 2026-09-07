package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * H2 权威存储中的不可变 Agent Role revision；内置版本只能 clone，不能编辑。
 *
 * @param id 稳定 Role 标识
 * @param revision 不可变版本，从 1 开始
 * @param lifecycle 当前版本生命周期
 * @param spec 行为定义，不包含执行授权
 * @param builtin 是否为发行版内置 Role
 * @param createdAt 首次创建时间
 * @param updatedAt 当前版本创建时间
 */
public record AgentRole(
        String id,
        long revision,
        RoleLifecycle lifecycle,
        AgentRoleSpec spec,
        boolean builtin,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验 Role 身份和时间顺序。 */
    public AgentRole {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** @return 本不可变版本的精确引用 */
    public AgentRoleRef ref() {
        return new AgentRoleRef(id, revision);
    }
}
