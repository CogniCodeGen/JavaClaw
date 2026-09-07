package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.TurnId;

/** 子智能体的 Protocol v3 契约；预算与安全边界在服务端从父 Turn 继承。 */
public final class CollaborationRpcContracts {
    private CollaborationRpcContracts() {}

    /**
     * 在活动父 Turn 下创建有界子智能体。
     *
     * @param parentTurnId 父 Turn 标识
     * @param agentType 角色稳定标识，由服务端解析活动版本
     * @param message 交办任务
     * @param execution 显式模型与可收窄执行选择，不可扩大父级权限或预算
     * @param title 子 Thread 可见标题
     */
    public record SpawnPayload(
            TurnId parentTurnId, String agentType, String message, ExecutionOverrides execution, String title) {
        /** 校验父级身份和任务内容。 */
        public SpawnPayload {
            Objects.requireNonNull(parentTurnId, "parentTurnId");
            agentType = text(agentType, "agentType");
            message = text(message, "message");
            Objects.requireNonNull(execution, "execution");
            title = text(title, "title");
        }
    }

    /**
     * 子智能体创建结果；仅返回执行配置安全摘要。
     *
     * @param thread 已持久化子 Thread
     * @param turn 已预留父预算的子 Turn
     * @param configuration 冻结执行摘要
     */
    public record SpawnResult(ConversationThread thread, AgentTurn turn, ResolvedTurnConfigSummary configuration) {
        /** 校验返回标识和摘要一致。 */
        public SpawnResult {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(turn, "turn");
            Objects.requireNonNull(configuration, "configuration");
            if (!turn.threadId().equals(thread.id()) || !configuration.equals(turn.resolvedConfig())) {
                throw new IllegalArgumentException("spawn result must identify one frozen child Turn");
            }
        }
    }

    /**
     * 查询一个已创建子 Turn 的当前状态。
     *
     * @param turnId 子 Turn
     */
    public record WaitPayload(TurnId turnId) {
        /** 校验子 Turn 标识。 */
        public WaitPayload {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 中断子 Turn；服务端先持久化取消意图再传播取消。
     *
     * @param turnId 子 Turn
     * @param reason 可见且脱敏的取消原因
     */
    public record InterruptPayload(TurnId turnId, String reason) {
        /** 校验标识及有界原因。 */
        public InterruptPayload {
            Objects.requireNonNull(turnId, "turnId");
            reason = text(reason, "reason");
            if (reason.length() > 500) {
                throw new IllegalArgumentException("reason must not exceed 500 characters");
            }
        }
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
