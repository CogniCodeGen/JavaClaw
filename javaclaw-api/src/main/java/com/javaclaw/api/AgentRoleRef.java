package com.javaclaw.api;

/**
 * 工作配置使用的精确 Agent Role 引用。
 *
 * @param id Role 稳定标识
 * @param revision 不可变版本，从 1 开始
 */
public record AgentRoleRef(String id, long revision) {
    /** 校验稳定标识和精确版本。 */
    public AgentRoleRef {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
    }
}
