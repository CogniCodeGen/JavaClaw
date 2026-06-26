package com.javaclaw.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 流式事件处理器
 *
 * <p>把 AgentScope 的 {@link Event} 按类型翻译为 {@link ConversationEvent} 推送给
 * {@link ConversationCallbacks}。对上层业务和 UI 完全屏蔽 AgentScope 的事件模型。</p>
 *
 * <p>支持的事件类型：
 * <ul>
 *   <li>{@code REASONING} → {@link ConversationEvent.Thinking}</li>
 *   <li>{@code AGENT_RESULT} → {@link ConversationEvent.Reply}</li>
 *   <li>{@code HINT} → {@link ConversationEvent.Hint}</li>
 *   <li>{@code TOOL_RESULT}（普通）→ {@link ConversationEvent.ToolResult}</li>
 *   <li>{@code TOOL_RESULT}（子智能体转发）→ {@link ConversationEvent.SubAgentThinking} /
 *       {@link ConversationEvent.SubAgentReply}</li>
 *   <li>所有事件中发现的 {@link ChatUsage} → {@link ConversationEvent.Usage}</li>
 * </ul>
 *
 * <p>子智能体事件优先通过 ToolResultBlock 的 metadata 以结构化对象形式获取；
 * 仅当 metadata 缺失时才降级到 JSON 解析。</p>
 */
public class StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamEventHandler.class);

    /** SubAgentTool 在 metadata 中存放原始 Event 对象的 key */
    private static final String META_SUBAGENT_EVENT = "subagent_event";

    /** SubAgentTool 在 metadata 中存放智能体名称的 key */
    private static final String META_SUBAGENT_NAME = "subagent_name";

    /** JSON 解析器（用于降级处理无 metadata 的转发事件） */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理单个流式事件，翻译为 {@link ConversationEvent} 并推送到回调。
     *
     * @param event     AgentScope 流式事件
     * @param callbacks 对话回调（事件接收方）
     */
    public void handleEvent(Event event, ConversationCallbacks callbacks) {
        EventType type = event.getType();
        Msg msg = event.getMessage();
        if (msg == null) {
            log.debug("收到空消息事件: type={}", type);
            return;
        }

        // 任意事件只要 Msg 携带 ChatUsage 都上报一次，避免遗漏编排器首轮/中间轮的 usage
        reportUsageIfPresent(msg, callbacks);

        try {
            switch (type) {
                case REASONING -> handleReasoning(msg, callbacks);
                case TOOL_RESULT -> handleToolResult(msg, callbacks);
                case HINT -> handleHint(msg, callbacks);
                case AGENT_RESULT -> handleAgentResult(msg, callbacks);
                default -> log.debug("忽略事件类型: {}", type);
            }
        } catch (Throwable t) {
            // 兜底：防止异常杀死 Reactor 线程，导致流中断
            log.error("处理事件时发生异常 (type={})，已跳过该事件", type, t);
        }
    }

    private void reportUsageIfPresent(Msg msg, ConversationCallbacks callbacks) {
        if (msg == null) return;
        try {
            ChatUsage usage = msg.getChatUsage();
            if (usage != null && (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0)) {
                callbacks.onEvent(new ConversationEvent.Usage(
                        usage.getInputTokens(), usage.getOutputTokens()));
            }
        } catch (Throwable t) {
            log.debug("读取 ChatUsage 失败，忽略", t);
        }
    }

    private void handleReasoning(Msg msg, ConversationCallbacks callbacks) {
        List<ThinkingBlock> blocks = msg.getContentBlocks(ThinkingBlock.class);
        if (blocks != null && !blocks.isEmpty()) {
            for (ThinkingBlock block : blocks) {
                String thinking = block.getThinking();
                if (thinking != null && !thinking.isEmpty()) {
                    log.debug("思考片段: {}", truncate(thinking, 50));
                    callbacks.onEvent(new ConversationEvent.Thinking(thinking));
                }
            }
        } else {
            String text = msg.getTextContent();
            if (text != null && !text.isEmpty()) {
                callbacks.onEvent(new ConversationEvent.Thinking(text));
            }
        }
    }

    private void handleHint(Msg msg, ConversationCallbacks callbacks) {
        String hintText = msg.getTextContent();
        if (hintText != null && !hintText.isEmpty()) {
            log.debug("规划提示: {}", truncate(hintText, 100));
            callbacks.onEvent(new ConversationEvent.Hint(hintText));
        }
    }

    private void handleAgentResult(Msg msg, ConversationCallbacks callbacks) {
        String text = msg.getTextContent();
        if (text != null && !text.isEmpty()) {
            log.debug("回复片段: {}", truncate(text, 50));
            callbacks.onEvent(new ConversationEvent.Reply(text));
        }
    }

    /**
     * 处理工具调用结果事件。
     *
     * <p>优先通过 ToolResultBlock 的 metadata 取得子智能体的结构化 Event；缺失时退化到
     * JSON 文本解析。普通工具调用直接发 {@link ConversationEvent.ToolResult}。</p>
     */
    private void handleToolResult(Msg msg, ConversationCallbacks callbacks) {
        List<ToolResultBlock> resultBlocks = msg.getContentBlocks(ToolResultBlock.class);
        if (resultBlocks != null && !resultBlocks.isEmpty()) {
            for (ToolResultBlock block : resultBlocks) {
                String toolName = block.getName() != null ? block.getName() : "unknown";

                // 优先：通过 metadata 获取结构化的子智能体 Event
                if (tryDispatchSubAgentFromMetadata(block, toolName, callbacks)) {
                    continue;
                }

                // 降级：提取文本内容，判断是否为转发事件 JSON
                String content = extractTextContent(block);
                if (content.isEmpty()) continue;

                if (isForwardedEventJson(content)) {
                    dispatchForwardedEventJson(toolName, content, callbacks);
                } else {
                    log.debug("子智能体 [{}] 返回结果: {} 字符", toolName, content.length());
                    callbacks.onEvent(new ConversationEvent.ToolResult(toolName, content));
                }
            }
        } else {
            // 降级：从 textContent 获取
            String text = msg.getTextContent();
            if (text != null && !text.isEmpty()) {
                if (isForwardedEventJson(text)) {
                    dispatchForwardedEventJson("unknown", text, callbacks);
                } else {
                    callbacks.onEvent(new ConversationEvent.ToolResult("unknown", text));
                }
            }
        }
    }

    // ==================== 结构化路径：通过 metadata 访问 ====================

    /**
     * 尝试从 ToolResultBlock 的 metadata 中获取子智能体的原始 Event 对象并处理
     *
     * @return true 表示成功处理，false 表示需要降级处理
     */
    private boolean tryDispatchSubAgentFromMetadata(ToolResultBlock block,
                                                     String toolName,
                                                     ConversationCallbacks callbacks) {
        Map<String, Object> metadata = block.getMetadata();
        if (metadata == null || !metadata.containsKey(META_SUBAGENT_EVENT)) {
            return false;
        }

        Object eventObj = metadata.get(META_SUBAGENT_EVENT);
        if (!(eventObj instanceof Event subEvent)) {
            return false;
        }

        String agentName = metadata.containsKey(META_SUBAGENT_NAME)
                ? String.valueOf(metadata.get(META_SUBAGENT_NAME)) : null;
        String effectiveAgent = (!"unknown".equals(toolName)) ? toolName : agentName;

        // 子智能体的模型调用 usage 也要累计
        reportUsageIfPresent(subEvent.getMessage(), callbacks);

        dispatchSubAgentEvent(effectiveAgent, subEvent, callbacks);
        return true;
    }

    /**
     * 根据子智能体事件类型分发为对应的 {@link ConversationEvent.SubAgentThinking} /
     * {@link ConversationEvent.SubAgentReply} / {@link ConversationEvent.ToolResult}。
     */
    private void dispatchSubAgentEvent(String agentName,
                                       Event subEvent,
                                       ConversationCallbacks callbacks) {
        EventType subType = subEvent.getType();
        Msg subMsg = subEvent.getMessage();
        if (subMsg == null) return;

        switch (subType) {
            case REASONING -> {
                ThinkingBlock thinkingBlock = subMsg.getFirstContentBlock(ThinkingBlock.class);
                String thinking = (thinkingBlock != null)
                        ? thinkingBlock.getThinking() : subMsg.getTextContent();
                if (thinking != null && !thinking.isEmpty()) {
                    log.debug("子智能体 [{}] 思考片段: {}", agentName, truncate(thinking, 30));
                    callbacks.onEvent(new ConversationEvent.SubAgentThinking(agentName, thinking));
                }
            }
            case AGENT_RESULT -> {
                String text = subMsg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    log.debug("子智能体 [{}] 回复: {} 字符", agentName, text.length());
                    callbacks.onEvent(new ConversationEvent.SubAgentReply(agentName, text));
                }
            }
            default -> {
                String text = subMsg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    callbacks.onEvent(new ConversationEvent.ToolResult(agentName, text));
                }
                log.debug("忽略子智能体转发事件类型: {}", subType);
            }
        }
    }

    // ==================== 降级路径：Jackson JSON 解析 ====================

    private boolean isForwardedEventJson(String content) {
        return content.startsWith("{\"type\":\"") && content.contains("\"message\":");
    }

    /**
     * 降级：当 metadata 不包含结构化 Event 对象时，从文本 JSON 解析转发事件。
     */
    private void dispatchForwardedEventJson(String toolName,
                                             String json,
                                             ConversationCallbacks callbacks) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String eventType = root.path("type").asText("");
            JsonNode msgNode = root.path("message");

            String agentName = msgNode.path("name").asText(null);
            String effectiveAgent = (toolName != null && !"unknown".equals(toolName))
                    ? toolName : agentName;

            JsonNode contentArray = msgNode.path("content");

            switch (eventType) {
                case "REASONING" -> {
                    String thinking = extractFieldFromContentArray(contentArray, "thinking", "thinking");
                    if (thinking == null || thinking.isEmpty()) {
                        thinking = extractFieldFromContentArray(contentArray, "text", "text");
                    }
                    if (thinking != null && !thinking.isEmpty()) {
                        callbacks.onEvent(new ConversationEvent.SubAgentThinking(effectiveAgent, thinking));
                    }
                }
                case "AGENT_RESULT" -> {
                    String text = extractFieldFromContentArray(contentArray, "text", "text");
                    if (text != null && !text.isEmpty()) {
                        callbacks.onEvent(new ConversationEvent.SubAgentReply(effectiveAgent, text));
                    }
                }
                default -> {
                    String text = extractFieldFromContentArray(contentArray, "text", "text");
                    if (text != null && !text.isEmpty()) {
                        callbacks.onEvent(new ConversationEvent.ToolResult(effectiveAgent, text));
                    }
                    log.debug("忽略子智能体转发事件: type={}", eventType);
                }
            }
        } catch (Exception e) {
            // JSON 解析失败，作为普通文本结果处理
            log.warn("解析转发事件 JSON 失败，作为普通结果处理: {}", truncate(json, 100), e);
            callbacks.onEvent(new ConversationEvent.ToolResult(toolName, json));
        }
    }

    /**
     * 从 JSON content 数组中提取指定类型块的字段值
     */
    private String extractFieldFromContentArray(JsonNode contentArray, String blockType, String fieldName) {
        if (contentArray == null || !contentArray.isArray()) return null;
        for (JsonNode block : contentArray) {
            if (blockType.equals(block.path("type").asText(""))) {
                String value = block.path(fieldName).asText(null);
                if (value != null && !value.isEmpty()) return value;
            }
        }
        return null;
    }

    /**
     * 从 ToolResultBlock 的 output 中提取所有文本内容
     */
    private String extractTextContent(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        if (block.getOutput() != null) {
            for (var contentBlock : block.getOutput()) {
                if (contentBlock instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    /** 截断文本用于日志输出 */
    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
