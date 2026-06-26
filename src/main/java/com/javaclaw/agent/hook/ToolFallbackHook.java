package com.javaclaw.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具失败降级钩子
 *
 * <p>在 PostActing 阶段检测工具连续失败，当同一工具连续失败达到阈值时，
 * 在工具结果中注入降级建议，引导编排器切换到替代工具。</p>
 *
 * <p>降级策略基于提示词引导（而非强制替换），保持编排器的自主决策权。</p>
 */
public class ToolFallbackHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolFallbackHook.class);

    /** 连续失败触发降级建议的阈值 */
    private static final int FAILURE_THRESHOLD = 2;

    /** 工具名 → 连续失败次数 */
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();

    /** 降级建议映射（工具名 → 降级提示） */
    private static final Map<String, String> FALLBACK_HINTS = Map.of(
            "web_expert", "网页浏览工具连续失败，建议：1) 使用 system_expert 进行截图操作替代；2) 或直接基于已知信息回答",
            "email_expert", "邮件工具连续失败，建议：1) 使用 notification_expert 通过其他渠道发送通知；2) 或告知用户邮件服务暂时不可用",
            "system_expert", "系统操作工具连续失败，建议：1) 检查操作权限是否足够；2) 或使用其他方式完成任务",
            "notification_expert", "通知工具连续失败，建议：1) 使用 email_expert 通过邮件替代通知；2) 或直接告知用户"
    );

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActing) {
            return handlePostActing(postActing).map(e -> event);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 在 LoopDetectionHook(10) 之后、AgentLoggingHook(200) 之前
        return 50;
    }

    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        String toolName = event.getToolUse() != null ? event.getToolUse().getName() : "unknown";
        String resultText = extractResultText(event.getToolResult());
        if (resultText == null || resultText.isBlank()) {
            return Mono.just(event);
        }

        // 检测是否为失败结果
        boolean isFailure = resultText.contains("[失败]") || resultText.contains("[超时]")
                || resultText.contains("[错误]");

        if (isFailure) {
            int count = toolFailureCount.merge(toolName, 1, Integer::sum);
            if (count >= FAILURE_THRESHOLD) {
                String hint = FALLBACK_HINTS.getOrDefault(toolName,
                        String.format("工具 %s 连续 %d 次失败，建议切换到其他工具或直接回答用户", toolName, count));
                log.warn("工具降级建议触发 — 工具: {}, 连续失败: {} 次", toolName, count);

                // 在工具结果末尾追加降级建议
                ToolResultBlock augmented = ToolResultBlock.builder()
                        .id(event.getToolResult().getId())
                        .name(event.getToolResult().getName())
                        .output(List.of(TextBlock.builder()
                                .text(resultText + "\n\n[降级建议] " + hint).build()))
                        .build();
                event.setToolResult(augmented);
            }
        } else {
            // 成功时重置该工具的失败计数
            toolFailureCount.remove(toolName);
        }

        return Mono.just(event);
    }

    /**
     * 从 ToolResultBlock 中提取文本
     */
    private String extractResultText(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.getOutput() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (var block : toolResult.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 重置所有失败计数
     */
    public void reset() {
        toolFailureCount.clear();
    }
}
