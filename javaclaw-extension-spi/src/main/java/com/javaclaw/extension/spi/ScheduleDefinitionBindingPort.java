package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;

/** 仅允许调用方扩展维护自己贡献的 Definition 调度绑定；平台注入所有者并走 Schedule 命令。 */
public interface ScheduleDefinitionBindingPort {
    /** @return 未装配内部绑定路由时明确拒绝的端口 */
    static ScheduleDefinitionBindingPort unavailable() {
        return new ScheduleDefinitionBindingPort() {
            @Override
            public Binding read(ExtensionId owner, WorkspaceId workspaceId, String definitionId) {
                throw new IllegalStateException("Schedule binding port is unavailable");
            }

            @Override
            public Binding bind(
                    ExtensionId owner, WorkspaceId workspaceId, Change change, CancellationToken cancellation) {
                throw new IllegalStateException("Schedule binding port is unavailable");
            }
        };
    }

    /**
     * 平台验证所有者后发送的内部查询载荷。
     *
     * @param owner 权威所有者
     * @param definitionId 所有者定义
     */
    record Lookup(ExtensionId owner, String definitionId) {
        /** 校验所有者与定义。 */
        public Lookup {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(definitionId, "definitionId");
        }
    }

    /**
     * 平台验证所有者后发送的内部写入载荷。
     *
     * @param owner 权威所有者
     * @param change 受限绑定变更
     */
    record Apply(ExtensionId owner, Change change) {
        /** 校验内部变更。 */
        public Apply {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(change, "change");
        }
    }

    /**
     * 读取调用方 Definition 的绑定；没有绑定时返回 revision/generation 为零的 UNBOUND 状态。
     *
     * @param owner 调用方扩展，平台必须验证其身份
     * @param workspaceId 当前 Workspace
     * @param definitionId 调用方贡献的 Definition
     * @return 当前绑定
     * @throws Exception 查询失败
     */
    Binding read(ExtensionId owner, WorkspaceId workspaceId, String definitionId) throws Exception;

    /**
     * 幂等创建或重定向绑定；不能修改用户管理的名称、频率、时区或启停。
     *
     * @param owner 调用方扩展，平台必须验证其身份
     * @param workspaceId 当前 Workspace
     * @param change 包含预期绑定版本与代次的请求
     * @param cancellation 协作式取消
     * @return 已提交绑定
     * @throws Exception 绑定冲突或执行失败
     */
    Binding bind(ExtensionId owner, WorkspaceId workspaceId, Change change, CancellationToken cancellation)
            throws Exception;

    /** 绑定状态；DETACHED 只能经用户明确重新安排创建新代次。 */
    enum State {
        UNBOUND,
        BOUND,
        DETACHED
    }

    /**
     * Schedule 拥有的绑定投影。
     *
     * @param definitionId 所有者 Definition
     * @param revision 绑定 CAS 版本
     * @param generation 调度代次
     * @param scheduleId 当前或已删除的 Schedule 标识，未绑定时为空
     * @param definitionRevision 当前绑定 Definition 版本
     * @param state 绑定状态
     */
    record Binding(
            String definitionId,
            long revision,
            long generation,
            String scheduleId,
            long definitionRevision,
            State state) {
        /** 校验绑定标识和版本。 */
        public Binding {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(scheduleId, "scheduleId");
            Objects.requireNonNull(state, "state");
            if (definitionId.isBlank() || revision < 0 || generation < 0 || definitionRevision < 0) {
                throw new IllegalArgumentException("binding identity or revision is invalid");
            }
        }
    }

    /**
     * 精确版本绑定意图；target 是所有者执行选择的规范 payload，由 Schedule 严格解码。
     *
     * @param definitionId 自己的 Definition
     * @param definitionRevision 精确版本
     * @param expectedBindingRevision 绑定预期版本
     * @param expectedGeneration 绑定预期代次
     * @param reschedule 是否经用户明确重新安排已删除的调度
     * @param target DefinitionTarget 规范 payload
     * @param idempotencyKey 稳定意图键
     */
    record Change(
            String definitionId,
            long definitionRevision,
            long expectedBindingRevision,
            long expectedGeneration,
            boolean reschedule,
            CanonicalPayload target,
            String idempotencyKey) {
        /** 校验版本、标识和规范输入。 */
        public Change {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (definitionId.isBlank()
                    || definitionRevision < 1
                    || expectedBindingRevision < 0
                    || expectedGeneration < 0
                    || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("binding change is invalid");
            }
        }
    }
}
