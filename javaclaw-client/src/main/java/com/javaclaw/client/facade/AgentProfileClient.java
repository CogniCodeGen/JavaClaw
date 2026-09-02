package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.ProviderProfileRpcContracts;

/** Agent Profile 与 Workspace/Thread 默认绑定的强类型 facade。 */
public final class AgentProfileClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public AgentProfileClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** @return 每个 Agent Profile 的最新版本 */
    public List<AgentProfile> list() {
        return connection
                .query("profile/list", Map.of(), ProviderProfileRpcContracts.AgentProfileListResult.class)
                .profiles();
    }

    /**
     * 读取精确版本。
     *
     * @param id Profile 标识
     * @param revision 版本
     * @return Profile
     */
    public AgentProfile read(String id, long revision) {
        return connection.query(
                "profile/read",
                new ProviderProfileRpcContracts.AgentProfileReadPayload(id, revision),
                AgentProfile.class);
    }

    /**
     * 创建 Profile。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param options expected revision 必须为 0
     * @return 首个版本
     */
    public AgentProfile create(String id, AgentProfileSpec spec, CommandOptions options) {
        return connection.command(
                "profile/create",
                new ProviderProfileRpcContracts.AgentProfileCreatePayload(id, spec),
                options,
                AgentProfile.class);
    }

    /**
     * 更新 Profile。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle ACTIVE 或 DISABLED
     * @param options expected revision 必须匹配当前版本
     * @return 新版本
     */
    public AgentProfile update(String id, AgentProfileSpec spec, ProfileLifecycle lifecycle, CommandOptions options) {
        return connection.command(
                "profile/update",
                new ProviderProfileRpcContracts.AgentProfileUpdatePayload(id, spec, lifecycle),
                options,
                AgentProfile.class);
    }

    /**
     * 归档 Profile。
     *
     * @param id Profile 标识
     * @param options expected revision 必须匹配当前版本
     * @return 归档版本
     */
    public AgentProfile archive(String id, CommandOptions options) {
        return connection.command(
                "profile/archive",
                new ProviderProfileRpcContracts.AgentProfileArchivePayload(id),
                options,
                AgentProfile.class);
    }

    /**
     * 预览下一 Turn 的 Prompt provenance。
     *
     * <p>结果包含 Core/Profile 可审阅文本，但项目约定、Skill 和 Context 只返回来源元数据。
     *
     * @param workspaceId 用于解析当前项目约定的 Workspace
     * @param profile 精确 Agent Profile
     * @return 来源、完整快照摘要和本地 token 估算
     */
    public PromptManifestPreview previewPrompt(WorkspaceId workspaceId, AgentProfileRef profile) {
        return connection.query(
                "profile/prompt/preview",
                new ProviderProfileRpcContracts.PromptPreviewPayload(workspaceId, profile),
                PromptManifestPreview.class);
    }

    /**
     * 读取直接绑定；不会从 Thread 回退到 Workspace。
     *
     * @param workspaceId Workspace
     * @param threadId Thread 覆盖；Workspace 默认时为空
     * @return 绑定
     */
    public Optional<ProfileBinding> binding(WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        return connection
                .query(
                        "profile/binding/read",
                        new ProviderProfileRpcContracts.ProfileBindingReadPayload(workspaceId, threadId),
                        ProviderProfileRpcContracts.ProfileBindingReadResult.class)
                .binding();
    }

    /**
     * 创建或替换直接绑定。
     *
     * @param workspaceId Workspace
     * @param threadId Thread 覆盖；Workspace 默认时为空
     * @param profile 精确 Profile
     * @param options 绑定自身的 expected revision
     * @return 已提交绑定
     */
    public ProfileBinding bind(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, AgentProfileRef profile, CommandOptions options) {
        return connection.command(
                "profile/binding/update",
                new ProviderProfileRpcContracts.ProfileBindingUpdatePayload(workspaceId, threadId, profile),
                options,
                ProfileBinding.class);
    }
}
