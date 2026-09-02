package com.javaclaw.api;

/**
 * Turn 或默认绑定使用的精确 Agent Profile 引用。
 *
 * @param id Profile 标识
 * @param revision 不可变版本
 */
public record AgentProfileRef(String id, long revision) {
    /** 校验引用。 */
    public AgentProfileRef {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
    }
}
