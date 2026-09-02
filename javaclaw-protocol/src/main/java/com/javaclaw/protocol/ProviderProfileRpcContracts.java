package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** Provider、Agent Profile 与默认绑定的 Protocol v2 payload。 */
public final class ProviderProfileRpcContracts {
    private ProviderProfileRpcContracts() {}

    /**
     * Provider 精确版本查询。
     *
     * @param id Provider 标识
     * @param revision 精确版本号
     */
    public record ProviderReadPayload(String id, long revision) {
        /** 校验查询。 */
        public ProviderReadPayload {
            id = text(id, "id");
            revision = positive(revision, "revision");
        }
    }

    /**
     * Provider 创建参数。
     *
     * @param id Provider 标识
     * @param spec Provider 配置
     */
    public record ProviderCreatePayload(String id, ProviderEndpointSpec spec) {
        /** 校验创建参数。 */
        public ProviderCreatePayload {
            id = text(id, "id");
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * Provider 完整更新参数。
     *
     * @param id Provider 标识
     * @param spec Provider 配置
     * @param lifecycle Provider 生命周期状态
     */
    public record ProviderUpdatePayload(String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        /** 校验更新参数。 */
        public ProviderUpdatePayload {
            id = text(id, "id");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    /**
     * Provider 归档参数。
     *
     * @param id Provider 标识
     */
    public record ProviderArchivePayload(String id) {
        /** 校验标识。 */
        public ProviderArchivePayload {
            id = text(id, "id");
        }
    }

    /**
     * Provider 本地探测参数。
     *
     * @param provider Provider 精确版本引用
     */
    public record ProviderProbePayload(ProviderRef provider) {
        /** 校验引用。 */
        public ProviderProbePayload {
            Objects.requireNonNull(provider, "provider");
        }
    }

    /**
     * Agent Profile 精确版本查询。
     *
     * @param id Agent Profile 标识
     * @param revision 精确版本号
     */
    public record AgentProfileReadPayload(String id, long revision) {
        /** 校验查询。 */
        public AgentProfileReadPayload {
            id = text(id, "id");
            revision = positive(revision, "revision");
        }
    }

    /**
     * Agent Profile 创建参数。
     *
     * @param id Agent Profile 标识
     * @param spec Agent Profile 配置
     */
    public record AgentProfileCreatePayload(String id, AgentProfileSpec spec) {
        /** 校验创建参数。 */
        public AgentProfileCreatePayload {
            id = text(id, "id");
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * Agent Profile 完整更新参数。
     *
     * @param id Agent Profile 标识
     * @param spec Agent Profile 配置
     * @param lifecycle Agent Profile 生命周期状态
     */
    public record AgentProfileUpdatePayload(String id, AgentProfileSpec spec, ProfileLifecycle lifecycle) {
        /** 校验更新参数。 */
        public AgentProfileUpdatePayload {
            id = text(id, "id");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    /**
     * Agent Profile 归档参数。
     *
     * @param id Agent Profile 标识
     */
    public record AgentProfileArchivePayload(String id) {
        /** 校验标识。 */
        public AgentProfileArchivePayload {
            id = text(id, "id");
        }
    }

    /**
     * 下一 Turn Prompt provenance 预览参数。
     *
     * @param workspaceId 用于解析当前项目约定的 Workspace
     * @param profile 精确 Agent Profile 版本
     */
    public record PromptPreviewPayload(WorkspaceId workspaceId, AgentProfileRef profile) {
        /** 校验作用域与 Profile 引用。 */
        public PromptPreviewPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * Workspace 或 Thread 直接 Profile 绑定查询。
     *
     * @param workspaceId Workspace 标识
     * @param threadId 可空的 Thread 标识；为空时查询 Workspace 默认绑定
     */
    public record ProfileBindingReadPayload(WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        /** 校验作用域。 */
        public ProfileBindingReadPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * Workspace 或 Thread 默认 Profile 绑定更新。
     *
     * @param workspaceId Workspace 标识
     * @param threadId 可空的 Thread 标识；为空时更新 Workspace 默认绑定
     * @param profile Agent Profile 精确版本引用
     */
    public record ProfileBindingUpdatePayload(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, AgentProfileRef profile) {
        /** 校验绑定。 */
        public ProfileBindingUpdatePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * Provider 最新版本列表。
     *
     * @param providers Provider 最新版本快照
     */
    public record ProviderListResult(List<ProviderEndpoint> providers) {
        /** 复制结果。 */
        public ProviderListResult {
            providers = List.copyOf(providers);
        }
    }

    /**
     * Agent Profile 最新版本列表。
     *
     * @param profiles Agent Profile 最新版本快照
     */
    public record AgentProfileListResult(List<AgentProfile> profiles) {
        /** 复制结果。 */
        public AgentProfileListResult {
            profiles = List.copyOf(profiles);
        }
    }

    /**
     * 可空的直接 Profile 绑定查询结果。
     *
     * @param binding 查询到的直接绑定；未设置时为空
     */
    public record ProfileBindingReadResult(Optional<ProfileBinding> binding) {
        /** 校验结果。 */
        public ProfileBindingReadResult {
            binding = Objects.requireNonNull(binding, "binding");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
