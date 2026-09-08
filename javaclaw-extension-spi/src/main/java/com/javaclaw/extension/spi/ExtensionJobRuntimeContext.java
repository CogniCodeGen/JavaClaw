package com.javaclaw.extension.spi;

import java.time.Clock;
import java.util.Objects;

/**
 * 创建内置扩展 Job executor 时可使用的稳定平台端口。
 *
 * <p>上下文不暴露 App Server Service 或 JDBC。executor 必须只依赖冻结输入和这些受治理端口，确保重启后可重建。
 *
 * @param clock 平台时钟
 * @param payloads 规范 payload codec
 * @param turns 单 Turn 编排端口
 * @param managedStore 内置扩展托管事务
 * @param services 进程外隔离服务端口
 * @param embeddings 向量嵌入端口
 * @param automationSteps Workflow 精确工具与输入步骤端口
 * @param scheduledCommands Schedule 显式命令端口
 * @param scheduleLifecycle Schedule 后台 lease 与登录启动项端口
 * @param conversationEvidence 有界公开对话证据
 * @param scheduleBindings 仅允许所属扩展 Definition 的内部调度绑定
 */
public record ExtensionJobRuntimeContext(
        Clock clock,
        ExtensionPayloadCodec payloads,
        TurnOrchestrationPort turns,
        ManagedExtensionStore managedStore,
        IsolatedServicePort services,
        EmbeddingPort embeddings,
        AutomationStepPort automationSteps,
        ScheduledCommandPort scheduledCommands,
        ScheduleLifecyclePort scheduleLifecycle,
        ConversationEvidencePort conversationEvidence,
        ScheduleDefinitionBindingPort scheduleBindings) {
    /** 校验所有端口。 */
    public ExtensionJobRuntimeContext {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(payloads, "payloads");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(managedStore, "managedStore");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(embeddings, "embeddings");
        Objects.requireNonNull(automationSteps, "automationSteps");
        Objects.requireNonNull(scheduledCommands, "scheduledCommands");
        Objects.requireNonNull(scheduleLifecycle, "scheduleLifecycle");
        Objects.requireNonNull(conversationEvidence, "conversationEvidence");
        Objects.requireNonNull(scheduleBindings, "scheduleBindings");
    }
}
