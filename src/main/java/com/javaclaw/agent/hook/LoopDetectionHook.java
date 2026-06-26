package com.javaclaw.agent.hook;

import com.javaclaw.config.AgentConfig;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 循环检测钩子（基于 AgentScope Hook 机制）
 *
 * <p>利用 AgentScope 的 {@link Hook} 接口，在工具调用的生命周期中
 * 自动检测连续重复的调用模式，防止智能体陷入死循环。</p>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>{@code PreActingEvent} — 记录即将调用的工具名称，用于日志追踪</li>
 *   <li>{@code PostActingEvent} — 提取工具调用结果，与历史结果比较相似度</li>
 *   <li>当连续相似结果超过阈值时，调用 {@code PostActingEvent.stopAgent()} 中断智能体</li>
 *   <li>同时修改工具结果，注入停止提示信息，引导模型输出最终回答</li>
 * </ol>
 *
 * @author JavaClaw
 * @see Hook
 * @see PostActingEvent
 */
public class LoopDetectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(LoopDetectionHook.class);

    /** 连续相同工具调用的最大允许次数 */
    private final int maxRepeats;

    /** 相似度阈值 */
    private final double similarityThreshold;

    /** 历史工具调用结果记录（滑动窗口，防止内存无限增长） */
    private static final int MAX_HISTORY_SIZE = 50;
    /** 需对"读 last + compare + 写入"三步整体加锁，避免并发 Hook 触发 CME */
    private final List<String> toolResultHistory = new ArrayList<>();
    private final Object historyLock = new Object();

    /** 当前连续重复计数 */
    private final AtomicInteger consecutiveRepeats = new AtomicInteger(0);

    /** 是否已触发循环检测 */
    private volatile boolean loopDetected = false;

    /** 工具连续错误计数（工具名 → 连续错误次数） */
    private final Map<String, Integer> toolErrorStreak = new ConcurrentHashMap<>();

    /**
     * 循环检测回调（用于通知 UI 层）
     * 使用 AtomicReference 以支持每次对话动态更新回调
     */
    private final AtomicReference<Consumer<String>> onLoopDetectedCallback = new AtomicReference<>();

    /** 交互式循环处理回调；若设置则优先于 onLoopDetectedCallback，支持用户选择继续或终止 */
    private final AtomicReference<LoopInteractiveHandler> interactiveHandler = new AtomicReference<>();

    /** 用户未响应的最长等待时间（超时视为终止） */
    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 交互式循环处理器：检测到循环时触发，由 UI 决定是继续还是终止
     *
     * <p>handler 收到三个参数：工具名、已连续次数、decisionCallback。
     * UI 必须调用 decisionCallback.accept(true/false) 来放行或终止，
     * 不响应将在 {@link #INTERACTIVE_TIMEOUT} 后自动终止。</p>
     */
    @FunctionalInterface
    public interface LoopInteractiveHandler {
        void onLoopDetected(String toolName, int repeats, Consumer<Boolean> decisionCallback);
    }

    /**
     * 使用默认配置创建
     */
    public LoopDetectionHook() {
        this(AgentConfig.getInstance().getMaxRepeatedToolCalls(),
                AgentConfig.getInstance().getLoopSimilarityThreshold());
    }

    /**
     * 使用自定义参数创建
     *
     * @param maxRepeats          连续重复的最大允许次数
     * @param similarityThreshold 相似度阈值（0.0~1.0）
     */
    public LoopDetectionHook(int maxRepeats, double similarityThreshold) {
        this.maxRepeats = maxRepeats;
        this.similarityThreshold = similarityThreshold;
        log.info("LoopDetectionHook 已创建 — maxRepeats: {}, similarityThreshold: {}",
                maxRepeats, similarityThreshold);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            return handlePreActing(preActing).map(e -> event);
        }
        if (event instanceof PostActingEvent postActing) {
            return handlePostActing(postActing).map(e -> event);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 较高优先级，确保在其他钩子之前执行循环检测
        return 10;
    }

    /**
     * 工具调用前：记录工具名称用于日志
     */
    private Mono<PreActingEvent> handlePreActing(PreActingEvent event) {
        String toolName = event.getToolUse() != null ? event.getToolUse().getName() : "unknown";
        log.debug("Hook 拦截工具调用: {}", toolName);
        return Mono.just(event);
    }

    /**
     * 工具调用后：提取结果、检测循环
     *
     * <p>检测到循环时：
     * <ul>
     *   <li>调用 {@code stopAgent()} 通知框架停止智能体迭代</li>
     *   <li>修改工具结果，注入提示信息引导模型输出最终回答</li>
     *   <li>触发 UI 回调通知用户</li>
     * </ul>
     * </p>
     */
    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        // 提取工具结果文本
        String resultText = extractResultText(event.getToolResult());
        if (resultText == null || resultText.isBlank()) {
            return Mono.just(event);
        }

        String toolName = event.getToolUse() != null ? event.getToolUse().getName() : "unknown";

        // 截取前 500 字符用于比较
        String normalized = resultText.length() > 500
                ? resultText.substring(0, 500) : resultText;

        // 与上一次结果比较（读 + 比较 + 计数更新 需整体加锁）
        int repeatsAfterUpdate = -1;
        synchronized (historyLock) {
            if (!toolResultHistory.isEmpty()) {
                String lastResult = toolResultHistory.get(toolResultHistory.size() - 1);
                double similarity = computeSimilarity(lastResult, normalized);

                if (similarity >= similarityThreshold) {
                    repeatsAfterUpdate = consecutiveRepeats.incrementAndGet();
                    log.warn("Hook 检测到重复工具调用 [{}] — 相似度: {}, 连续次数: {}/{}",
                            toolName, String.format("%.2f", similarity),
                            repeatsAfterUpdate, maxRepeats);
                } else {
                    consecutiveRepeats.set(0);
                }
            }
        }

        if (repeatsAfterUpdate >= maxRepeats) {
            loopDetected = true;
            log.error("Hook 触发死循环中断！工具 [{}] 连续 {} 次相似调用",
                    toolName, repeatsAfterUpdate);

            LoopInteractiveHandler interactive = interactiveHandler.get();
            if (interactive != null) {
                return awaitUserDecision(event, toolName, repeatsAfterUpdate, interactive);
            }
            return applyStopImmediately(event, toolName, repeatsAfterUpdate);
        }

        // 第二层检测：同一工具连续错误
        boolean isError = normalized.contains("[失败]") || normalized.contains("[超时]")
                || normalized.contains("[错误]") || normalized.contains("error")
                || normalized.contains("失败");
        if (isError) {
            int streak = toolErrorStreak.merge(toolName, 1, Integer::sum);
            int errorThreshold = Math.max(2, maxRepeats / 2);
            if (streak >= errorThreshold && !loopDetected) {
                log.warn("工具 [{}] 连续 {} 次错误调用，注入切换建议", toolName, streak);

                String errorHint = String.format(
                        "\n[系统提示] 工具 %s 连续 %d 次返回错误，建议切换到其他工具或直接基于已有信息回答用户。",
                        toolName, streak);

                ToolResultBlock augmented = ToolResultBlock.builder()
                        .id(event.getToolResult().getId())
                        .name(event.getToolResult().getName())
                        .output(List.of(TextBlock.builder()
                                .text(normalized + errorHint).build()))
                        .build();
                event.setToolResult(augmented);
            }
        } else {
            toolErrorStreak.remove(toolName);
        }

        synchronized (historyLock) {
            toolResultHistory.add(normalized);
            if (toolResultHistory.size() > MAX_HISTORY_SIZE) {
                toolResultHistory.remove(0);
            }
        }
        return Mono.just(event);
    }

    /**
     * 立即停止并注入中断提示（无交互式处理器时走此路径）
     */
    private Mono<PostActingEvent> applyStopImmediately(PostActingEvent event, String toolName, int repeats) {
        event.stopAgent();
        event.setToolResult(buildStopHint(event, toolName, repeats));

        Consumer<String> callback = onLoopDetectedCallback.get();
        if (callback != null) {
            callback.accept(String.format(
                    "检测到死循环：工具 [%s] 连续 %d 次相似调用，已自动中断。", toolName, repeats));
        }
        return Mono.just(event);
    }

    /**
     * 暂停执行，等待用户决定继续或终止
     *
     * <p>返回的 Mono 在用户做出选择或超时后才会完成，借此阻塞 Reactor 链。</p>
     */
    private Mono<PostActingEvent> awaitUserDecision(PostActingEvent event,
                                                     String toolName,
                                                     int repeats,
                                                     LoopInteractiveHandler handler) {
        CompletableFuture<Boolean> cf = new CompletableFuture<>();
        try {
            handler.onLoopDetected(toolName, repeats, decision ->
                    cf.complete(decision != null && decision));
        } catch (Exception e) {
            log.error("调用交互式循环处理器失败", e);
            return applyStopImmediately(event, toolName, repeats);
        }

        return Mono.fromFuture(cf)
                .timeout(INTERACTIVE_TIMEOUT, Mono.just(false))
                .map(continueRunning -> {
                    if (Boolean.TRUE.equals(continueRunning)) {
                        log.info("用户选择继续，重置循环计数并放行 [{}]", toolName);
                        consecutiveRepeats.set(0);
                        synchronized (historyLock) {
                            toolResultHistory.clear();
                        }
                        loopDetected = false;
                        return event;
                    }
                    log.info("用户选择终止或超时，中断智能体 [{}]", toolName);
                    event.stopAgent();
                    event.setToolResult(buildStopHint(event, toolName, repeats));
                    return event;
                });
    }

    private ToolResultBlock buildStopHint(PostActingEvent event, String toolName, int repeats) {
        String stopHint = String.format(
                "[系统中断] 检测到对工具 %s 的连续 %d 次重复调用，已自动停止。"
                        + "请直接基于已有信息给出最终回答，不要再调用工具。",
                toolName, repeats);
        return ToolResultBlock.builder()
                .id(event.getToolResult().getId())
                .name(event.getToolResult().getName())
                .output(List.of(TextBlock.builder().text(stopHint).build()))
                .build();
    }

    /**
     * 从 ToolResultBlock 中提取文本内容
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
     * 计算两个字符串的相似度（基于 bigram Jaccard 系数）
     *
     * <p>将字符串拆分为连续的双字符组（bigram），计算两个集合的 Jaccard 系数。
     * 相比逐位置字符匹配，该算法对内容偏移、插入/删除更鲁棒，
     * 能更准确地检测内容实质相似的重复调用。</p>
     *
     * @return 相似度值 0.0~1.0
     */
    private double computeSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        // 字符串太短时退化为精确比较
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        // 构建 bigram 多重集合（使用 Map 记录频次）
        Map<String, Integer> bigramsA = buildBigramMap(a);
        Map<String, Integer> bigramsB = buildBigramMap(b);

        // 计算交集和并集大小
        int intersection = 0;
        int union = 0;
        Set<String> allBigrams = new java.util.HashSet<>(bigramsA.keySet());
        allBigrams.addAll(bigramsB.keySet());
        for (String bigram : allBigrams) {
            int countA = bigramsA.getOrDefault(bigram, 0);
            int countB = bigramsB.getOrDefault(bigram, 0);
            intersection += Math.min(countA, countB);
            union += Math.max(countA, countB);
        }
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private Map<String, Integer> buildBigramMap(String s) {
        Map<String, Integer> map = new java.util.HashMap<>();
        for (int i = 0; i < s.length() - 1; i++) {
            String bigram = s.substring(i, i + 2);
            map.merge(bigram, 1, Integer::sum);
        }
        return map;
    }

    /**
     * 设置循环检测回调（每次新对话前调用）
     *
     * @param callback 检测到循环时的通知回调，传入警告消息
     */
    public void setOnLoopDetected(Consumer<String> callback) {
        onLoopDetectedCallback.set(callback);
    }

    /**
     * 设置交互式循环处理器（优先级高于 {@link #setOnLoopDetected}）
     *
     * <p>一旦设置，检测到循环时 hook 会暂停并等待 UI 决定继续或终止。</p>
     */
    public void setLoopInteractiveHandler(LoopInteractiveHandler handler) {
        interactiveHandler.set(handler);
    }

    /**
     * 是否已检测到循环
     */
    public boolean isLoopDetected() {
        return loopDetected;
    }

    /**
     * 重置检测状态（每次新对话前调用）
     */
    public void reset() {
        synchronized (historyLock) {
            toolResultHistory.clear();
        }
        consecutiveRepeats.set(0);
        loopDetected = false;
        toolErrorStreak.clear();
        log.debug("LoopDetectionHook 已重置");
    }
}
