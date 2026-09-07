package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.UnattendedExecutionScope;

/**
 * 创建 Turn、解析配置、Prompt、目录和首条消息的同事务参数。
 *
 * @param threadId 所属 Thread
 * @param configuration 唯一完整冻结配置
 * @param executionRoot 服务端验证的绝对执行根
 * @param promptSnapshot 分层 Prompt 与来源正文，仅服务端可读
 * @param toolCatalog 冻结目录，持久化时重新绑定真实 Turn ID
 * @param message 首条用户消息
 * @param unattendedExecutionScope 无人值守来源；普通 Turn 为空
 * @param codingEnvironment 事务外准备的项目声明及精确工具链；旧内部调用可为空，由 Core 补齐
 */
public record TurnStartRequest(
        ThreadId threadId,
        ResolvedTurnConfig configuration,
        Path executionRoot,
        CanonicalPayload promptSnapshot,
        ToolCatalogSnapshot toolCatalog,
        CorePayloads.Message message,
        Optional<UnattendedExecutionScope> unattendedExecutionScope,
        Optional<com.javaclaw.server.toolchain.CodingEnvironmentSelection> codingEnvironment) {
    /** 校验快照之间的摘要一致性，禁止拼接来自不同解析的配置。 */
    public TurnStartRequest {
        Objects.requireNonNull(threadId, "threadId");
        codingEnvironment = Objects.requireNonNull(codingEnvironment, "codingEnvironment");
        Objects.requireNonNull(configuration, "configuration");
        executionRoot = Objects.requireNonNull(executionRoot, "executionRoot")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(promptSnapshot, "promptSnapshot");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        Objects.requireNonNull(message, "message");
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
        if (!configuration.promptManifestDigest().equals(promptSnapshot.sha256())
                || !configuration.toolCatalogDigest().equals(toolCatalog.digest())) {
            throw new IllegalArgumentException("解析配置与 Prompt 或工具目录摘要不一致");
        }
    }

    /**
     * 兼容旧内部创建路径；Coding 选择由 Core 在创建事务前准备。
     *
     * @param threadId 所属 Thread
     * @param configuration 已冻结配置
     * @param executionRoot 权威执行根
     * @param promptSnapshot Prompt 快照
     * @param toolCatalog 工具目录
     * @param message 首条消息
     * @param unattendedExecutionScope 无人值守来源
     */
    public TurnStartRequest(
            ThreadId threadId,
            ResolvedTurnConfig configuration,
            Path executionRoot,
            CanonicalPayload promptSnapshot,
            ToolCatalogSnapshot toolCatalog,
            CorePayloads.Message message,
            Optional<UnattendedExecutionScope> unattendedExecutionScope) {
        this(
                threadId,
                configuration,
                executionRoot,
                promptSnapshot,
                toolCatalog,
                message,
                unattendedExecutionScope,
                Optional.empty());
    }

    /**
     * 绑定已完成受限读取的选择，不改变已有的模型、提示词和权限快照。
     *
     * @param selection 权威准备结果
     * @return 包含 Coding 环境的创建参数
     */
    public TurnStartRequest withCodingEnvironment(com.javaclaw.server.toolchain.CodingEnvironmentSelection selection) {
        return new TurnStartRequest(
                threadId,
                configuration,
                executionRoot,
                promptSnapshot,
                toolCatalog,
                message,
                unattendedExecutionScope,
                Optional.of(selection));
    }
}
