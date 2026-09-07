package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.RoleLifecycle;

/** Agent Role 的 Protocol v3 强类型请求；写入统一使用幂等和 revision 信封。 */
public final class AgentRoleRpcContracts {
    private AgentRoleRpcContracts() {}

    /**
     * 精确 Role 版本查询。
     *
     * @param role 不可空的精确版本引用
     */
    public record ReadPayload(AgentRoleRef role) {
        /** 校验版本引用。 */
        public ReadPayload {
            Objects.requireNonNull(role, "role");
        }
    }

    /**
     * 自定义 Role 创建参数。
     *
     * @param id 稳定标识
     * @param spec 行为与能力收窄，不包含权限授权
     */
    public record CreatePayload(String id, AgentRoleSpec spec) {
        /** 校验标识与定义。 */
        public CreatePayload {
            id = new AgentRoleRef(id, 1).id();
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * 自定义 Role 更新参数；内置 Role 必须先 clone。
     *
     * @param id 稳定标识
     * @param spec 完整角色定义
     * @param lifecycle ACTIVE 或 DISABLED
     */
    public record UpdatePayload(String id, AgentRoleSpec spec, RoleLifecycle lifecycle) {
        /** 校验定义并禁止以更新绕过归档契约。 */
        public UpdatePayload {
            id = new AgentRoleRef(id, 1).id();
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (lifecycle == RoleLifecycle.ARCHIVED) {
                throw new IllegalArgumentException("use agent/role/archive to archive a Role");
            }
        }
    }

    /**
     * Role 归档参数。
     *
     * @param id 稳定标识
     */
    public record ArchivePayload(String id) {
        /** 校验标识。 */
        public ArchivePayload {
            id = new AgentRoleRef(id, 1).id();
        }
    }

    /**
     * 将精确 Role 版本复制成新的自定义角色。
     *
     * @param source 精确来源版本
     * @param id 新的稳定标识
     * @param name 新的可见名称
     */
    public record ClonePayload(AgentRoleRef source, String id, String name) {
        /** 校验来源和新名称。 */
        public ClonePayload {
            Objects.requireNonNull(source, "source");
            id = new AgentRoleRef(id, 1).id();
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    /**
     * Role 最新版本目录。
     *
     * @param roles 不可变版本快照
     */
    public record ListResult(List<AgentRole> roles) {
        /** 冻结结果列表。 */
        public ListResult {
            roles = List.copyOf(roles);
        }
    }
}
