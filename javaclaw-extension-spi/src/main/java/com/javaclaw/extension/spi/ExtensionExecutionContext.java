package com.javaclaw.extension.spi;

import java.time.Clock;
import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.WorkspaceId;

/**
 * 单次扩展调用上下文。
 *
 * @param extension 扩展描述
 * @param workspaceId 当前调用或恢复所属 Workspace
 * @param effectivePermissions 多级策略求交后的最终权限
 * @param cancellation 协作式取消信号
 * @param clock 平台时钟
 * @param managedStore 内置扩展托管存储
 * @param turns 单 Turn 编排端口
 * @param executionPolicies 自动化执行权威冻结端口
 * @param scheduleTargets Schedule 可选目标的权威目录
 * @param inputs 受治理用户输入请求端口
 * @param jobs 可恢复扩展作业端口
 * @param evidence Core Item 逐字证据核验端口
 * @param attachments Workspace-owned Core Attachment 核验端口
 * @param credentials Vault 脱敏元数据端口
 * @param privateNetworkGrants 私网授权绑定校验端口
 * @param services 进程外隔离服务端口
 * @param embeddings 向量嵌入端口
 */
public record ExtensionExecutionContext(
        ExtensionDescriptor extension,
        WorkspaceId workspaceId,
        PermissionProfile effectivePermissions,
        CancellationToken cancellation,
        Clock clock,
        ManagedExtensionStore managedStore,
        TurnOrchestrationPort turns,
        AutomationExecutionPolicyPort executionPolicies,
        ScheduleTargetCatalogPort scheduleTargets,
        InputRequestPort inputs,
        ExtensionJobPort jobs,
        ItemEvidencePort evidence,
        AttachmentEvidencePort attachments,
        CredentialVaultPort credentials,
        PrivateNetworkGrantPort privateNetworkGrants,
        IsolatedServicePort services,
        EmbeddingPort embeddings) {
    /** 校验上下文端口。 */
    public ExtensionExecutionContext {
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(effectivePermissions, "effectivePermissions");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(managedStore, "managedStore");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(executionPolicies, "executionPolicies");
        Objects.requireNonNull(scheduleTargets, "scheduleTargets");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(privateNetworkGrants, "privateNetworkGrants");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(embeddings, "embeddings");
    }
}
