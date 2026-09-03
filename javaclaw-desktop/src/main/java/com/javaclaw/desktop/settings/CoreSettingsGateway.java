package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/**
 * 管理中心访问 Java SDK 的异步强类型边界。
 *
 * <p>所有 CompletionStage 必须在 JavaFX 调度器上完成；实现不得让页面直接访问 App Server Service、H2 或传输层。
 */
public interface CoreSettingsGateway {
    /** @return 最新 Provider 目录 */
    CompletionStage<List<ProviderEndpoint>> providers();

    /**
     * 创建 Provider。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 初始状态；只有 DISABLED 允许空模型目录
     * @param options 幂等与 revision
     * @return 创建结果
     */
    CompletionStage<ProviderEndpoint> createProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options);

    /**
     * 更新 Provider。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 生命周期
     * @param options 幂等与 revision
     * @return 更新结果
     */
    CompletionStage<ProviderEndpoint> updateProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options);

    /**
     * 归档 Provider。
     *
     * @param id 稳定标识
     * @param options 幂等与 revision
     * @return 归档结果
     */
    CompletionStage<ProviderEndpoint> archiveProvider(String id, CommandOptions options);

    /**
     * 读取已保存 Provider 精确版本的远程模型目录，不执行推理。
     *
     * @param id Provider 标识
     * @param revision 精确版本
     * @param cancellation 页面或作用域取消信号
     * @return 有界候选目录
     */
    CompletionStage<ProviderModelDiscoveryResult> discoverProviderModels(
            String id, long revision, CancellationToken cancellation);

    /** @return 本地安装的精确 Embedding 默认绑定 */
    CompletionStage<Optional<EmbeddingBinding>> embeddingBinding();

    /**
     * 设置本地安装的精确 Embedding 模型。
     *
     * @param provider 支持 Embedding 的精确 ProviderRef
     * @param options 绑定自身的 expected revision
     * @return 新绑定
     */
    CompletionStage<EmbeddingBinding> bindEmbedding(ProviderRef provider, CommandOptions options);

    /**
     * 执行不产生模型费用的本地 Provider 探测。
     *
     * @param provider 精确 Provider 引用
     * @return 脱敏状态
     */
    CompletionStage<ProviderStatus> probeProvider(ProviderRef provider);

    /**
     * 执行可能计费的最小 Provider round-trip。
     *
     * @param provider 精确 Provider 与模型
     * @param purpose 本次要验证的模型用途
     * @param billingConfirmed 必须为 true 的显式确认
     * @param confirmation 固定危险确认文本
     * @param options 幂等与 Provider revision
     * @return 脱敏延迟、usage、能力和终态
     */
    CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
            ProviderRef provider,
            ProviderModelPurpose purpose,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options);

    /**
     * 原子绑定或轮换 Provider Secret。
     *
     * @param provider 当前 Provider
     * @param credentialExpectedRevision 未绑定时为 0，轮换时为当前凭据版本
     * @param secret PasswordField 临时字符；实现必须清零副本
     * @param options 幂等与 Provider revision
     * @return Provider 新版本与脱敏凭据元数据
     */
    CompletionStage<ProviderCredentialBinding> setProviderCredential(
            ProviderEndpoint provider, long credentialExpectedRevision, char[] secret, CommandOptions options);

    /**
     * 原子解除 Provider 引用并清除 Secret。
     *
     * @param provider 当前 Provider
     * @param credential 当前凭据元数据
     * @param options 幂等与 Provider revision
     * @return Provider 新版本与清除回执
     */
    CompletionStage<ProviderCredentialClearResult> clearProviderCredential(
            ProviderEndpoint provider, CredentialMetadata credential, CommandOptions options);

    /** @return 最新 Agent Profile 目录 */
    CompletionStage<List<AgentProfile>> profiles();

    /**
     * 读取不可变的精确 Agent Profile。
     *
     * @param reference 精确引用
     * @return Profile 快照
     */
    CompletionStage<AgentProfile> profile(AgentProfileRef reference);

    /**
     * 创建 Agent Profile。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param options 幂等与 revision
     * @return 创建结果
     */
    CompletionStage<AgentProfile> createProfile(String id, AgentProfileSpec spec, CommandOptions options);

    /**
     * 更新 Agent Profile。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 生命周期
     * @param options 幂等与 revision
     * @return 更新结果
     */
    CompletionStage<AgentProfile> updateProfile(
            String id, AgentProfileSpec spec, ProfileLifecycle lifecycle, CommandOptions options);

    /**
     * 归档 Agent Profile。
     *
     * @param id 稳定标识
     * @param options 幂等与 revision
     * @return 归档结果
     */
    CompletionStage<AgentProfile> archiveProfile(String id, CommandOptions options);

    /** @return 最新 PermissionProfile 目录 */
    CompletionStage<List<PermissionProfile>> permissionProfiles();

    /**
     * 读取不可变的精确 PermissionProfile。
     *
     * @param reference 精确引用
     * @return 权限快照
     */
    CompletionStage<PermissionProfile> permissionProfile(PermissionProfileRef reference);

    /**
     * 查询固定 Workspace 和权限版本下的工具目录。
     *
     * <p>提供 Agent Profile 时返回其实际可执行目录；省略时返回权限编辑器的可选候选。目录 revision 始终由服务端计算。
     *
     * @param workspaceId 固定 Workspace
     * @param permissionProfile 精确权限版本
     * @param agentProfile 精确 Agent Profile
     * @param query 查询词
     * @param limit 最大结果数
     * @return 权威目录版本与有界结果
     */
    CompletionStage<ToolCatalogQueryResult> toolCatalog(
            WorkspaceId workspaceId,
            PermissionProfileRef permissionProfile,
            Optional<AgentProfileRef> agentProfile,
            String query,
            int limit);

    /**
     * 读取指定 PermissionProfile 的不可变版本历史。
     *
     * @param id 配置标识
     * @return revision 升序历史
     */
    CompletionStage<List<PermissionProfile>> permissionProfileHistory(String id);

    /**
     * 比较同一 PermissionProfile 的两个服务端版本。
     *
     * @param id 配置标识
     * @param beforeVersion 基准版本
     * @param afterVersion 比较版本
     * @return 权威分区差异
     */
    CompletionStage<PermissionProfileDiff> permissionProfileDiff(String id, long beforeVersion, long afterVersion);

    /** @return 当前 Workspace 目录，供有效权限预览选择作用域 */
    CompletionStage<List<Workspace>> workspaces();

    /**
     * 重命名 Workspace；根目录保持不变。
     *
     * @param workspace 当前版本
     * @param name 新名称
     * @param options 幂等与 Workspace revision
     * @return 新版本 Workspace
     */
    CompletionStage<Workspace> renameWorkspace(Workspace workspace, String name, CommandOptions options);

    /**
     * 归档 Workspace 登记；不会删除用户目录。
     *
     * @param workspace 当前版本
     * @param options 幂等与 Workspace revision
     * @return 已归档 Workspace
     */
    CompletionStage<Workspace> archiveWorkspace(Workspace workspace, CommandOptions options);

    /**
     * 读取 Workspace 直接默认 Agent Profile 绑定。
     *
     * @param workspaceId Workspace
     * @return 当前绑定
     */
    CompletionStage<Optional<ProfileBinding>> workspaceProfileBinding(WorkspaceId workspaceId);

    /**
     * 创建或替换 Workspace 默认 Agent Profile。
     *
     * @param workspaceId Workspace
     * @param profile 精确 Profile 版本
     * @param options 绑定自身的 expected revision
     * @return 新绑定
     */
    CompletionStage<ProfileBinding> bindWorkspaceProfile(
            WorkspaceId workspaceId, com.javaclaw.api.AgentProfileRef profile, CommandOptions options);

    /**
     * 列出平台为写型子 Thread 创建的受管 Worktree。
     *
     * @param workspaceId Workspace
     * @param includeCleaned 是否包含已清理记录
     * @return 受管 Worktree 最新快照
     */
    CompletionStage<List<ManagedWorktree>> managedWorktrees(WorkspaceId workspaceId, boolean includeCleaned);

    /**
     * 中断 Worktree 绑定的写型子 Thread。
     *
     * @param worktree 当前 Worktree
     * @param reason 脱敏原因
     * @param options 当前 expected revision
     * @return 更新后的 Worktree
     */
    CompletionStage<ManagedWorktree> interruptManagedWorktree(
            ManagedWorktree worktree, String reason, CommandOptions options);

    /**
     * 生成有界 Patch Attachment，不修改真实 Git index。
     *
     * @param worktree 当前 Worktree
     * @param options 当前 expected revision
     * @return 内容寻址 Patch
     */
    CompletionStage<ManagedWorktreeArtifact> exportManagedWorktreePatch(
            ManagedWorktree worktree, CommandOptions options);

    /**
     * 生成 cleanup 前必须具备的可验证 Backup Attachment。
     *
     * @param worktree 当前 Worktree
     * @param options 当前 expected revision
     * @return 内容寻址 Backup
     */
    CompletionStage<ManagedWorktreeArtifact> backupManagedWorktree(ManagedWorktree worktree, CommandOptions options);

    /**
     * 永久清理已备份且非活动的受管目录。
     *
     * @param worktree 当前 Worktree
     * @param confirmation 固定危险确认文本
     * @param options 当前 expected revision
     * @return CLEANED 快照
     */
    CompletionStage<ManagedWorktree> cleanupManagedWorktree(
            ManagedWorktree worktree, String confirmation, CommandOptions options);

    /**
     * 在主窗口导航至父或子 Thread；此操作不经过服务端写命令。
     *
     * @param threadId Thread
     * @return 已选择的 Thread
     */
    CompletionStage<ConversationThread> navigateToThread(ThreadId threadId);

    /**
     * 由服务端从精确模板版本 clone 新权限配置。
     *
     * @param source 精确源版本
     * @param newId 新配置标识
     * @param options 幂等选项，expected revision 为 0
     * @return 新配置的首个版本
     */
    CompletionStage<PermissionProfile> clonePermissionProfile(
            PermissionProfileRef source, String newId, CommandOptions options);

    /**
     * 写入 PermissionProfile 新版本。
     *
     * @param profile 完整新版本
     * @param options 幂等与上一版本
     * @return 保存结果
     */
    CompletionStage<PermissionProfile> updatePermissionProfile(PermissionProfile profile, CommandOptions options);

    /**
     * 由服务端权威计算五层有效权限交集。
     *
     * @param workspaceId Workspace
     * @param profile 精确权限版本
     * @param turnGrant 可选 Turn grant 模拟层
     * @param toolDeclaration 可选工具声明模拟层
     * @return 五层预览与拒绝原因
     */
    CompletionStage<EffectivePermissionPreview> effectivePermissionPreview(
            WorkspaceId workspaceId,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration);

    /**
     * 生成规范化私网授权预览，创建前必须由用户确认。
     *
     * @param workspaceId Workspace
     * @param purpose 精确用途
     * @param origin HTTPS Origin
     * @param dnsAddresses 当前解析得到的数字地址集合
     * @param validity 有效期；空值使用平台默认值
     * @return 带确认摘要的预览
     */
    CompletionStage<PrivateNetworkGrantPreview> previewPrivateNetworkGrant(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity);

    /** @param workspaceId Workspace @return 私网授权最新版本 */
    CompletionStage<List<PrivateNetworkGrant>> privateNetworkGrants(WorkspaceId workspaceId);

    /**
     * 提交用户已经确认的私网授权预览。
     *
     * @param preview 服务端生成的预览
     * @param options 幂等选项，expected revision 为 0
     * @return 新授权
     */
    CompletionStage<PrivateNetworkGrant> createPrivateNetworkGrant(
            PrivateNetworkGrantPreview preview, CommandOptions options);

    /**
     * 不可逆撤销私网授权。
     *
     * @param grant 当前授权版本
     * @param options 当前 expected revision
     * @return tombstone 版本
     */
    CompletionStage<PrivateNetworkGrant> revokePrivateNetworkGrant(PrivateNetworkGrant grant, CommandOptions options);

    /** @param workspaceId Workspace @return 无人值守授权与使用余额 */
    CompletionStage<List<UnattendedToolGrantStatus>> unattendedToolGrants(WorkspaceId workspaceId);

    /**
     * 创建 Schedule 专用的严格限额 Tool Grant。
     *
     * @param draft 完整授权草稿
     * @param options 幂等选项，expected revision 为 0
     * @return 新授权
     */
    CompletionStage<UnattendedToolGrant> createUnattendedToolGrant(
            UnattendedToolGrantDraft draft, CommandOptions options);

    /**
     * 不可逆撤销无人值守授权。
     *
     * @param grant 当前授权版本
     * @param options 当前 expected revision
     * @return tombstone 版本
     */
    CompletionStage<UnattendedToolGrant> revokeUnattendedToolGrant(UnattendedToolGrant grant, CommandOptions options);

    /**
     * 读取授权的脱敏决策轨迹。
     *
     * @param workspaceId Workspace
     * @param kind 授权类型
     * @param grantId 可选授权标识
     * @param limit 最大返回数
     * @return 新到旧排列的决策记录
     */
    CompletionStage<List<PermissionDecisionTrace>> permissionDecisions(
            WorkspaceId workspaceId, SecurityGrantKind kind, Optional<String> grantId, int limit);

    /** @return 脱敏 Vault 状态 */
    CompletionStage<VaultStatus> vaultStatus();

    /** @return 重新尝试解封后的脱敏 Vault 状态 */
    CompletionStage<VaultStatus> refreshVault();

    /**
     * 原子轮换 Vault 主密钥。
     *
     * @param options 幂等选项，expected revision 为 0
     * @return 脱敏回执
     */
    CompletionStage<VaultManagementReceipt> rotateVaultMasterKey(CommandOptions options);

    /**
     * 永久重置 Vault。
     *
     * @param confirmation 必须精确为 RESET VAULT
     * @param options 幂等选项，expected revision 为 0
     * @return 脱敏回执
     */
    CompletionStage<VaultManagementReceipt> resetVault(String confirmation, CommandOptions options);

    /**
     * 查询 Secret 的脱敏元数据。
     *
     * @param reference Vault 引用
     * @return 元数据
     */
    CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference);

    /**
     * 列出一个 Vault 命名空间中的脱敏元数据。
     *
     * @param namespace 精确命名空间
     * @return 按 opaque ID 排序的凭据元数据
     */
    CompletionStage<List<CredentialMetadata>> credentials(String namespace);

    /**
     * 创建 Secret。
     *
     * @param namespace 隔离命名空间
     * @param secret 敏感字符；实现必须清零
     * @param options 幂等与 revision
     * @return 脱敏元数据
     */
    CompletionStage<CredentialMetadata> createCredential(String namespace, char[] secret, CommandOptions options);

    /**
     * 轮换 Secret。
     *
     * @param reference Vault 引用
     * @param secret 敏感字符；实现必须清零
     * @param options 幂等与 revision
     * @return 脱敏元数据
     */
    CompletionStage<CredentialMetadata> rotateCredential(
            CredentialRef reference, char[] secret, CommandOptions options);

    /**
     * 清除 Secret。
     *
     * @param reference Vault 引用
     * @param options 幂等与 revision
     * @return 清除回执
     */
    CompletionStage<CredentialClearReceipt> clearCredential(CredentialRef reference, CommandOptions options);

    /** @return 当前 SDK 会话的脱敏连接摘要 */
    CompletionStage<ConnectionSummary> connection();

    /**
     * 关闭旧 SDK 会话并重新执行本地连接与 Protocol v2 初始化。
     *
     * @return 新会话脱敏摘要
     */
    CompletionStage<ConnectionSummary> reconnect();

    /**
     * 读取只包含白名单字段的 App Server 诊断快照。
     *
     * @return 脱敏诊断快照
     */
    CompletionStage<DiagnosticsSnapshot> diagnostics();

    /**
     * 读取发行 launcher 与托盘 supervisor 的真实可用状态。
     *
     * @return IDEA 直跑或托盘缺失时包含明确原因
     */
    CompletionStage<DiagnosticsRpcContracts.LauncherStatus> launcherStatus();

    /**
     * 请求受客户端和活动 lease 门禁的 App Server 协作式停止。
     *
     * @param options expected revision 必须为 0
     * @return 权威接受或拒绝决策
     */
    CompletionStage<DiagnosticsRpcContracts.ServerStopResult> stopServer(CommandOptions options);

    /**
     * 按 Schedule 权威状态修复系统登录启动项。
     *
     * @param options expected revision 必须为 0
     * @return 修复后的脱敏诊断快照
     */
    CompletionStage<DiagnosticsSnapshot> repairLoginStartup(CommandOptions options);
}
