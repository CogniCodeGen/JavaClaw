package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolCatalogSnapshot;

/**
 * Thin Harness 执行单个 Turn 的完整输入。
 *
 * @param turn 已持久化的 QUEUED Turn
 * @param provider 精确 Provider 与模型引用
 * @param systemInstruction 已审阅的内置系统说明
 * @param userMessage 当前用户消息
 * @param effectivePermissions 多级权限求交结果
 * @param toolCatalog Turn 启动时冻结的工具目录
 */
public record TurnExecutionCommand(
        AgentTurn turn,
        ProviderRef provider,
        String systemInstruction,
        String userMessage,
        PermissionProfile effectivePermissions,
        ToolCatalogSnapshot toolCatalog) {
    /** 校验命令。 */
    public TurnExecutionCommand {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(provider, "provider");
        systemInstruction = Objects.requireNonNull(systemInstruction, "systemInstruction");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(effectivePermissions, "effectivePermissions");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        if (!turn.provider().equals(provider)
                || effectivePermissions.version() < turn.permissionProfile().version()
                || !toolCatalog.turnId().equals(turn.id())
                || !toolCatalog.digest().equals(turn.toolCatalogDigest())) {
            throw new IllegalArgumentException("effectivePermissions must include the frozen profile revision");
        }
    }

    /**
     * 返回 ModelGateway 的不透明精确路由键。
     *
     * @return 路由键
     */
    public String modelRoute() {
        return provider.routeKey();
    }
}
