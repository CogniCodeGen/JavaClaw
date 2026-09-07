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
 * @param instructions 已审阅并冻结的分层说明
 * @param userMessage 当前用户消息
 * @param effectivePermissions 多级权限求交结果
 * @param toolCatalog Turn 启动时冻结的工具目录
 * @param contextPolicy 冻结的模型窗口政策，与 Turn 累计预算分别生效
 */
public record TurnExecutionCommand(
        AgentTurn turn,
        ProviderRef provider,
        ModelInstructions instructions,
        String userMessage,
        PermissionProfile effectivePermissions,
        ToolCatalogSnapshot toolCatalog,
        ModelContextPolicy contextPolicy) {
    /** 校验命令。 */
    public TurnExecutionCommand {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(instructions, "instructions");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(effectivePermissions, "effectivePermissions");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        Objects.requireNonNull(contextPolicy, "contextPolicy");
        if (!turn.provider().equals(provider)
                || effectivePermissions.version() < turn.permissionProfile().version()
                || !toolCatalog.turnId().equals(turn.id())
                || !toolCatalog.digest().equals(turn.toolCatalogDigest())) {
            throw new IllegalArgumentException("effectivePermissions must include the frozen profile revision");
        }
    }

    /**
     * 为未提供容量元数据的调用方使用明确的平台 fallback。
     *
     * @param turn 持久 Turn
     * @param provider 精确模型
     * @param instructions 冻结指令
     * @param userMessage 用户输入
     * @param effectivePermissions 有效权限
     * @param toolCatalog 冻结目录
     */
    public TurnExecutionCommand(
            AgentTurn turn,
            ProviderRef provider,
            ModelInstructions instructions,
            String userMessage,
            PermissionProfile effectivePermissions,
            ToolCatalogSnapshot toolCatalog) {
        this(
                turn,
                provider,
                instructions,
                userMessage,
                effectivePermissions,
                toolCatalog,
                ModelContextPolicy.fallback());
    }

    /**
     * 构造仅有平台指令的执行命令，复用同一 Harness 边界。
     *
     * @param turn 已持久化 Turn
     * @param provider 精确模型
     * @param systemInstruction 平台指令
     * @param userMessage 用户输入
     * @param effectivePermissions 有效权限
     * @param toolCatalog 冻结目录
     */
    public TurnExecutionCommand(
            AgentTurn turn,
            ProviderRef provider,
            String systemInstruction,
            String userMessage,
            PermissionProfile effectivePermissions,
            ToolCatalogSnapshot toolCatalog) {
        this(
                turn,
                provider,
                new ModelInstructions(systemInstruction, "", ""),
                userMessage,
                effectivePermissions,
                toolCatalog);
    }

    /** @return 平台指令层；其他层通过 instructions 显式传递 */
    public String systemInstruction() {
        return instructions.systemInstruction();
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
