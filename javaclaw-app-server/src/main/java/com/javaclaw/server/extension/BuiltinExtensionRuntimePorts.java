package com.javaclaw.server.extension;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AttachmentEvidencePort;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.InputRequestPort;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.extension.spi.ItemEvidencePort;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.TurnOrchestrationPort;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;

/**
 * 内置扩展 Host 使用的显式运行端口。
 *
 * @param clock 平台时钟
 * @param payloads 契约 codec
 * @param managedStore 内置扩展托管事务
 * @param turns 单 Turn 编排端口
 * @param executionPolicies 自动化执行权威冻结端口
 * @param inputs 受治理用户输入请求端口
 * @param jobs 可恢复扩展作业端口
 * @param evidence Core Item 逐字证据核验端口
 * @param attachments 按当前调用 Workspace 绑定 Core Attachment 核验端口的工厂
 * @param credentials Vault 脱敏元数据端口
 * @param privateNetworkGrants 私网授权绑定校验端口
 * @param services 受监督的进程外服务端口
 * @param embeddings 模型 Adapter 提供的向量嵌入端口
 * @param automationSteps Workflow 精确工具与输入步骤端口
 * @param scheduledCommands Schedule 显式命令端口
 * @param scheduleLifecycle Schedule 后台 lease 与登录启动项端口
 * @param catalog 持久扩展目录
 * @param conversationEvidence 已完成对话的有界证据端口
 * @param scheduleBindings 为扩展绑定所有者身份的调度端口工厂
 */
public record BuiltinExtensionRuntimePorts(
        Clock clock,
        ExtensionPayloadCodec payloads,
        ManagedExtensionStore managedStore,
        TurnOrchestrationPort turns,
        AutomationExecutionPolicyPort executionPolicies,
        InputRequestPort inputs,
        ExtensionJobPort jobs,
        ItemEvidencePort evidence,
        Function<WorkspaceId, AttachmentEvidencePort> attachments,
        CredentialVaultPort credentials,
        PrivateNetworkGrantPort privateNetworkGrants,
        IsolatedServicePort services,
        EmbeddingPort embeddings,
        AutomationStepPort automationSteps,
        ScheduledCommandPort scheduledCommands,
        ScheduleLifecyclePort scheduleLifecycle,
        ExtensionCatalogRepository catalog,
        com.javaclaw.extension.spi.ConversationEvidencePort conversationEvidence,
        Function<com.javaclaw.extension.spi.ExtensionId, com.javaclaw.extension.spi.ScheduleDefinitionBindingPort>
                scheduleBindings) {
    /** 校验所有端口。 */
    public BuiltinExtensionRuntimePorts {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(payloads, "payloads");
        Objects.requireNonNull(managedStore, "managedStore");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(executionPolicies, "executionPolicies");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(privateNetworkGrants, "privateNetworkGrants");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(embeddings, "embeddings");
        Objects.requireNonNull(automationSteps, "automationSteps");
        Objects.requireNonNull(scheduledCommands, "scheduledCommands");
        Objects.requireNonNull(scheduleLifecycle, "scheduleLifecycle");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(conversationEvidence, "conversationEvidence");
        Objects.requireNonNull(scheduleBindings, "scheduleBindings");
    }
}
