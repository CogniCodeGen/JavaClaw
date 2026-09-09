package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** 安装、Workspace 与 Thread 执行配置的 Protocol v3 契约。 */
public final class ExecutionRpcContracts {
    private ExecutionRpcContracts() {}

    /** 最近选择的全局只读查询；不存在时不返回安装默认值。 */
    public record RecentReadPayload() {
        /** 创建不携带作用域的查询。 */
        public RecentReadPayload {}
    }

    /**
     * 最近模型与思考选择的替换请求；它只用于初始化新 Thread。
     *
     * @param execution 模型与思考覆盖，不可空；服务端拒绝角色、权限、审批、预算和能力字段
     */
    public record RecentUpdatePayload(ExecutionOverrides execution) {
        /** 校验覆盖容器。 */
        public RecentUpdatePayload {
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * 轻量执行配置预览参数，不携带消息或项目资料。
     *
     * @param workspaceId 固定目标 Workspace，不可空
     * @param threadId 可选已有 Thread，服务端校验归属
     * @param execution 临时执行覆盖，不可空
     */
    public record PreviewPayload(WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        /** 校验参数容器；不存在或不可用的引用由服务端返回阻塞项。 */
        public PreviewPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * 默认配置查询范围。
     *
     * @param workspaceId 空值读取安装默认，有值读取 Workspace 直接覆盖
     */
    public record DefaultReadPayload(Optional<WorkspaceId> workspaceId) {
        /** 校验可选范围。 */
        public DefaultReadPayload {
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * 替换直接默认配置；移除某项意味着继承上级。
     *
     * @param workspaceId 空值写入安装默认，有值写入 Workspace 覆盖
     * @param execution 独立执行覆盖，不可空
     */
    public record DefaultUpdatePayload(Optional<WorkspaceId> workspaceId, ExecutionOverrides execution) {
        /** 校验范围与配置。 */
        public DefaultUpdatePayload {
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * Thread 直接覆盖查询。
     *
     * @param workspaceId 所属 Workspace
     * @param threadId Thread
     */
    public record ThreadReadPayload(WorkspaceId workspaceId, ThreadId threadId) {
        /** 校验范围；所属关系由服务端验证。 */
        public ThreadReadPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * 替换 Thread 直接执行覆盖。
     *
     * @param workspaceId 所属 Workspace
     * @param threadId Thread
     * @param execution 独立执行覆盖，不可空
     */
    public record ThreadUpdatePayload(WorkspaceId workspaceId, ThreadId threadId, ExecutionOverrides execution) {
        /** 校验范围与配置。 */
        public ThreadUpdatePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * 直接配置查询结果，不隐式替换成上级配置。
     *
     * @param configuration 尚未配置时为空
     */
    public record ReadResult(Optional<ExecutionConfiguration> configuration) {
        /** 校验可选结果。 */
        public ReadResult {
            configuration = Objects.requireNonNull(configuration, "configuration");
        }
    }
}
