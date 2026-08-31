package com.javaclaw.agent.tool;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 工具前置安全 Hook 与后置审计 Hook 的契约集合；Hook 不是 OS 安全边界。 */
public final class ToolHooks {
    private ToolHooks() {}

    /** 同步前置决策 Hook；超时、异常和拒绝均阻止执行。 */
    @FunctionalInterface
    public interface PreToolHook {
        /**
         * 检查或改写调用提案；新参数会重新校验 Schema，返回策略只能进一步收窄。
         *
         * @throws Exception 检查失败，调用必须失败关闭
         */
        PreToolDecision apply(Invocation invocation) throws Exception;
    }

    /** 异步审计 Hook；错误不得改变已经完成的工具结果。 */
    @FunctionalInterface
    public interface PostToolHook {
        /**
         * 观察调用和脱敏结果；失败由治理层尝试记录 ErrorItem，不回滚工具副作用。
         *
         * @throws Exception 审计处理失败
         */
        void accept(Invocation invocation, ToolExecutionResult result) throws Exception;
    }

    /**
     * Hook 可观察的调用提案，包含来源、风险和当前最窄策略。
     *
     * @param call 当前工具调用及 Thread/Turn 配置快照，调用时非空
     * @param origin 工具来源，用于策略和审计分类
     * @param risk 声明的风险级别，用于决定审批要求
     * @param arguments 工具参数 JSON；Hook 改写后必须重新通过 Schema 校验
     * @param policy 当前权限上限；后续策略只能求交收窄，不能扩大
     */
    public record Invocation(
            ToolExecutionContext call, ToolOrigin origin, ToolRisk risk, JsonNode arguments, SandboxPolicy policy) {}

    /**
     * 前置 Hook 决策；即使 allowed 为 true，也仍需审批和 OS 沙箱。
     *
     * @param allowed 是否允许进入后续治理步骤
     * @param reason 拒绝原因；null 归一为空字符串
     * @param arguments 工具参数 JSON；Hook 改写后必须重新通过 Schema 校验
     * @param policy 当前权限上限；后续策略只能求交收窄，不能扩大
     */
    public record PreToolDecision(boolean allowed, String reason, JsonNode arguments, SandboxPolicy policy) {
        /** 要求参数和策略非空；允许/拒绝决议均保留完整提案以便审计。 */
        public PreToolDecision {
            reason = reason == null ? "" : reason;
            arguments = Objects.requireNonNull(arguments, "arguments");
            policy = Objects.requireNonNull(policy, "policy");
        }

        /** 保留现有参数和策略，允许继续后续治理；不跳过审批。 */
        public static PreToolDecision allow(Invocation value) {
            return new PreToolDecision(true, "", value.arguments(), value.policy());
        }

        /** 保留现有提案并附拒绝原因，终止本次工具调用。 */
        public static PreToolDecision deny(Invocation value, String reason) {
            return new PreToolDecision(false, reason, value.arguments(), value.policy());
        }
    }
}
