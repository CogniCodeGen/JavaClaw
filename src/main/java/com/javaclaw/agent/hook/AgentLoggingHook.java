package com.javaclaw.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import com.javaclaw.diagnostics.TraceRecorder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体全生命周期日志钩子
 *
 * <p>覆盖 AgentScope 全部 9 种 Hook 事件，记录智能体执行的每个阶段：
 * <ul>
 *   <li>{@code PreCallEvent} — 智能体调用开始</li>
 *   <li>{@code PostCallEvent} — 智能体调用结束</li>
 *   <li>{@code PreReasoningEvent} — LLM 推理前（记录输入消息数量）</li>
 *   <li>{@code PostReasoningEvent} — LLM 推理后（记录输出内容摘要）</li>
 *   <li>{@code ReasoningChunkEvent} — 推理流式块</li>
 *   <li>{@code PreActingEvent} — 工具执行前（记录工具名称和参数）</li>
 *   <li>{@code PostActingEvent} — 工具执行后（记录工具结果摘要）</li>
 *   <li>{@code ActingChunkEvent} — 工具执行流式块</li>
 *   <li>{@code ErrorEvent} — 错误发生</li>
 * </ul>
 *
 * @author JavaClaw
 * @see Hook
 */
public class AgentLoggingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AgentLoggingHook.class);

    /** 日志中内容摘要的最大字符数 */
    private static final int MAX_LOG_CONTENT_LENGTH = 200;

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent e) {
            handlePreCall(e);
        } else if (event instanceof PostCallEvent e) {
            handlePostCall(e);
        } else if (event instanceof PreReasoningEvent e) {
            handlePreReasoning(e);
        } else if (event instanceof PostReasoningEvent e) {
            handlePostReasoning(e);
        } else if (event instanceof ReasoningChunkEvent e) {
            handleReasoningChunk(e);
        } else if (event instanceof PreActingEvent e) {
            handlePreActing(e);
        } else if (event instanceof PostActingEvent e) {
            handlePostActing(e);
        } else if (event instanceof ActingChunkEvent e) {
            handleActingChunk(e);
        } else if (event instanceof ErrorEvent e) {
            handleError(e);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 日志钩子优先级较低，在业务钩子之后执行
        return 200;
    }

    /**
     * 智能体调用前触发
     */
    private void handlePreCall(PreCallEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        log.debug("[Hook:PreCall] 智能体 [{}] 开始处理", agentName);
    }

    /**
     * 智能体调用后触发
     */
    private void handlePostCall(PostCallEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        Msg resultMsg = event.getFinalMessage();
        String resultSummary = summarizeMsg(resultMsg);
        log.debug("[Hook:PostCall] 智能体 [{}] 处理完成 — 结果: {}", agentName, resultSummary);
    }

    /**
     * LLM 推理前触发
     */
    private void handlePreReasoning(PreReasoningEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        List<Msg> inputMessages = event.getInputMessages();
        int msgCount = inputMessages != null ? inputMessages.size() : 0;
        log.debug("[Hook:PreReasoning] 智能体 [{}] 即将进行 LLM 推理 — 输入消息数: {}", agentName, msgCount);
        if (log.isDebugEnabled() && inputMessages != null && !inputMessages.isEmpty()) {
            Msg lastMsg = inputMessages.get(inputMessages.size() - 1);
            log.debug("[Hook:PreReasoning] 最后一条消息角色: {}, 内容: {}",
                    lastMsg.getRole(), truncate(summarizeMsg(lastMsg)));
        }
    }

    /**
     * LLM 推理后触发
     */
    private void handlePostReasoning(PostReasoningEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        Msg reasoningResult = event.getReasoningMessage();
        String summary = summarizeMsg(reasoningResult);
        log.debug("[Hook:PostReasoning] 智能体 [{}] LLM 推理完成 — 结果: {}", agentName, summary);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", truncate(summary));
        TraceRecorder.getInstance().record(agentName, "model_call", fields);
    }

    /**
     * 推理流式块触发（高频事件，使用 TRACE 级别避免刷屏）
     */
    private void handleReasoningChunk(ReasoningChunkEvent event) {
        if (log.isTraceEnabled()) {
            String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
            log.trace("[Hook:ReasoningChunk] 智能体 [{}] 推理流式块", agentName);
        }
    }

    /**
     * 工具执行前触发
     */
    private void handlePreActing(PreActingEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        String toolName = event.getToolUse() != null ? event.getToolUse().getName() : "unknown";
        String toolInput = event.getToolUse() != null && event.getToolUse().getInput() != null
                ? truncate(event.getToolUse().getInput().toString()) : "";
        log.info("[Hook:PreActing] 智能体 [{}] 即将执行工具 [{}] — 参数: {}",
                agentName, toolName, toolInput);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", toolName);
        fields.put("input", toolInput);
        TraceRecorder.getInstance().record(agentName, "tool_call", fields);
    }

    /**
     * 工具执行后触发
     */
    private void handlePostActing(PostActingEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        String toolName = event.getToolUse() != null ? event.getToolUse().getName() : "unknown";
        String resultText = extractResultText(event.getToolResult());
        log.info("[Hook:PostActing] 智能体 [{}] 工具 [{}] 执行完成 — 结果: {}",
                agentName, toolName, truncate(resultText));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", toolName);
        fields.put("result", truncate(resultText));
        TraceRecorder.getInstance().record(agentName, "tool_result", fields);
    }

    /**
     * 工具执行流式块触发（高频事件，使用 TRACE 级别）
     */
    private void handleActingChunk(ActingChunkEvent event) {
        if (log.isTraceEnabled()) {
            String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
            log.trace("[Hook:ActingChunk] 智能体 [{}] 工具执行流式块", agentName);
        }
    }

    /**
     * 错误发生时触发
     */
    private void handleError(ErrorEvent event) {
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
        Throwable error = event.getError();
        String errorMsg = error != null ? error.getMessage() : "未知错误";
        String errorType = error != null ? error.getClass().getSimpleName() : "Unknown";
        log.error("[Hook:Error] 智能体 [{}] 发生错误 — 类型: {}, 消息: {}",
                agentName, errorType, errorMsg);
        if (error != null && log.isDebugEnabled()) {
            log.debug("[Hook:Error] 智能体 [{}] 错误堆栈:", agentName, error);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("errorType", errorType);
        fields.put("message", errorMsg);
        TraceRecorder.getInstance().record(agentName, "error", fields);
    }

    /**
     * 提取消息内容摘要
     */
    private String summarizeMsg(Msg msg) {
        if (msg == null) {
            return "<空>";
        }
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return "<无内容>";
        }
        return content.stream()
                .map(block -> {
                    if (block instanceof TextBlock textBlock) {
                        return truncate(textBlock.getText());
                    }
                    return "[" + block.getClass().getSimpleName() + "]";
                })
                .collect(Collectors.joining(", "));
    }

    /**
     * 从 ToolResultBlock 中提取文本内容
     */
    private String extractResultText(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.getOutput() == null) {
            return "<空>";
        }
        StringBuilder sb = new StringBuilder();
        for (var block : toolResult.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText());
            }
        }
        return sb.isEmpty() ? "<无文本内容>" : sb.toString();
    }

    /**
     * 截断过长内容
     */
    private String truncate(String text) {
        if (text == null) {
            return "<null>";
        }
        if (text.length() <= MAX_LOG_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LOG_CONTENT_LENGTH) + "...(共" + text.length() + "字符)";
    }
}
