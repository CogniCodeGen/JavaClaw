package com.javaclaw.api.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.agent.evaluation.EvaluationResult;

import java.util.Objects;

/**
 * JavaFX 对话视图的兼容事件投影。
 *
 * <p>框架的权威、可扩展事件协议是
 * {@link com.javaclaw.framework.api.RunEventEnvelope}；此类型只存在于产品 UI 适配层，
 * 不得被协议端口、扩展或运行存储当作事件模型。</p>
 *
 * <p>事件可粗分为几类：</p>
 * <ul>
 *   <li><b>编排器增量</b>：{@link Thinking} / {@link Reply} / {@link ToolResult} / {@link Hint}</li>
 *   <li><b>子智能体增量</b>：{@link SubAgentThinking} / {@link SubAgentReply}（携带子智能体名）</li>
 *   <li><b>多智能体广播</b>：{@link AgentStart} / {@link AgentReply}（规划模式）</li>
 *   <li><b>过程可观测</b>：{@link Usage} / {@link Evaluation} / {@link LoopDetected}</li>
 *   <li><b>扩展</b>：{@link Custom}（供未来模式携带自定义负载）</li>
 * </ul>
 *
 * <p>本类型不依赖任何 UI 框架。</p>
 */
public interface ConversationEvent {

    /** 编排器思考过程增量（模型推理链的可见部分） */
    record Thinking(String chunk) implements ConversationEvent {}

    /** 编排器普通回复增量 */
    record Reply(String chunk) implements ConversationEvent {}

    /** 工具调用结果（工具名 + 结果文本） */
    record ToolResult(String toolName, String result) implements ConversationEvent {}

    /** 进度提示（如"协调者正在分析任务..."） */
    record Hint(String text) implements ConversationEvent {}

    /**
     * 子智能体思考片段（取代旧版 {@code \0SUB_THINKING\0} 文本标记）。
     *
     * @param agentName 子智能体名称（工具名或 agent.getName()）
     * @param chunk     思考文本片段
     */
    record SubAgentThinking(String agentName, String chunk) implements ConversationEvent {}

    /**
     * 子智能体回复片段（取代旧版 {@code \0SUB_REPLY\0} 文本标记）。
     *
     * @param agentName 子智能体名称
     * @param chunk     回复文本片段
     */
    record SubAgentReply(String agentName, String chunk) implements ConversationEvent {}

    /** 多智能体模式：某智能体开始发言，UI 据此创建独立气泡 */
    record AgentStart(String agentName) implements ConversationEvent {}

    /** 多智能体模式：某智能体回复内容的增量 */
    record AgentReply(String agentName, String chunk) implements ConversationEvent {}

    /**
     * 本轮模型调用的真实 token 用量增量（每次 API 返回都会触发一次）。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    record Usage(
            long inputTokens,
            long cacheReadInputTokens,
            long cacheWriteInputTokens,
            long outputTokens,
            long reasoningTokens,
            long modelCalls) implements ConversationEvent {
        public Usage {
            inputTokens = Math.max(0, inputTokens);
            cacheReadInputTokens = Math.min(inputTokens, Math.max(0, cacheReadInputTokens));
            cacheWriteInputTokens = Math.min(
                    inputTokens - cacheReadInputTokens, Math.max(0, cacheWriteInputTokens));
            outputTokens = Math.max(0, outputTokens);
            reasoningTokens = Math.min(outputTokens, Math.max(0, reasoningTokens));
            modelCalls = Math.max(0, modelCalls);
        }

        public Usage(long inputTokens, long outputTokens) {
            this(inputTokens, 0, 0, outputTokens, 0,
                    inputTokens > 0 || outputTokens > 0 ? 1 : 0);
        }
    }

    /** GEPA 过程评估结果 */
    record Evaluation(EvaluationResult result) implements ConversationEvent {}

    /** 循环检测：连续相似工具调用被识别为循环，UI 据此展示确认对话 */
    record LoopDetected(String warning) implements ConversationEvent {}

    /**
     * 管线阶段进度事件 — 让 UI 在「发送 → 收到首字」之间动态展示
     * 视觉预处理 / 意图识别 / 目标分解 / 知识检索 / 上下文整理 / 编排执行 等步骤的状态。
     *
     * <p>UI 端按 {@link #stageId()} 维护一行记录，收到同 stageId 的新事件时原地更新状态。
     * 跳过/失败状态附带 {@link #detail()} 解释（如"路由禁用"、"未命中文档"）。</p>
     *
     * @param stageId    稳定标识，UI 用此匹配阶段（如 "vision" / "intent" / "goal" / "rag" / "memory" / "build" / "orchestrate"）
     * @param stageLabel 显示名称（如"意图识别"），新建行时使用
     * @param status     阶段状态
     * @param detail     可选详情（结果摘要 / 跳过原因 / 错误消息），可为 null
     */
    record Progress(String stageId, String stageLabel, Status status, String detail) implements ConversationEvent {
        public enum Status {
            /** 阶段开始执行 */
            RUNNING,
            /** 阶段完成（成功） */
            DONE,
            /** 阶段被跳过（条件不满足、未配置等） */
            SKIPPED,
            /** 阶段执行失败（不中断主流程，UI 显示错误标记） */
            ERROR
        }
    }

    /**
     * 扩展事件：供未来模式携带自定义负载，避免每次新事件类型都修改 sealed 家族。
     *
     * @param kind    事件类型标识（由模式自行定义，消费者按 kind 判断如何处理）
     * @param payload JSON 负载；不允许携带任意 Java 对象
     */
    record Custom(String kind, JsonNode payload) implements ConversationEvent {
        public Custom {
            kind = Objects.requireNonNull(kind, "kind").trim();
            if (kind.isEmpty()) throw new IllegalArgumentException("event kind must not be blank");
            payload = payload == null ? NullNode.getInstance() : payload.deepCopy();
        }

        public Custom(String kind, String payload) {
            this(kind, TextNode.valueOf(payload == null ? "" : payload));
        }

        @Override public JsonNode payload() { return payload.deepCopy(); }
    }
}
