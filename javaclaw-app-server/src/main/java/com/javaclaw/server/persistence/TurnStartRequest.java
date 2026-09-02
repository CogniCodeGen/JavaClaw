package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.UnattendedExecutionScope;

/**
 * 同事务创建 Turn 与首条用户消息的语义参数。
 *
 * @param threadId 所属 Thread
 * @param budget 冻结预算
 * @param profile 冻结 Agent Profile
 * @param provider 冻结 Provider 与模型
 * @param permissionProfile 冻结权限配置
 * @param executionRoot 服务端解析的规范绝对执行根
 * @param promptSnapshot Prompt 来源与完整 system prompt 的冻结快照
 * @param toolCatalog 冻结工具目录；Core 会将其重新绑定到实际 Turn ID 后持久化
 * @param message 首条用户消息
 * @param unattendedExecutionScope Schedule 无人值守来源；普通 Turn 为空
 */
public record TurnStartRequest(
        ThreadId threadId,
        TurnBudget budget,
        AgentProfileRef profile,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        Path executionRoot,
        CanonicalPayload promptSnapshot,
        ToolCatalogSnapshot toolCatalog,
        CorePayloads.Message message,
        Optional<UnattendedExecutionScope> unattendedExecutionScope) {
    /** 校验参数；权限快照内容由服务端另行解析。 */
    public TurnStartRequest {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        executionRoot = Objects.requireNonNull(executionRoot, "executionRoot")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(promptSnapshot, "promptSnapshot");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        Objects.requireNonNull(message, "message");
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
    }

    /**
     * 返回持久化到 AgentTurn 的 Prompt manifest 摘要。
     *
     * @return 冻结 snapshot 的 SHA-256
     */
    public String promptManifestDigest() {
        return promptSnapshot.sha256();
    }

    /**
     * 返回持久化到 AgentTurn 的冻结目录摘要。
     *
     * @return 工具目录 SHA-256
     */
    public String toolCatalogDigest() {
        return toolCatalog.digest();
    }
}
