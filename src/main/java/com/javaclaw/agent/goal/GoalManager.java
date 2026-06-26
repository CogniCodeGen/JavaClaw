package com.javaclaw.agent.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.prompt.GoalPrompts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标管理器（GEPA 之 Goal）：将用户请求分解为可验证的目标 + 结构化成功准则。
 *
 * <p>分解结果注入编排器系统提示词，帮助智能体明确执行方向；结构化准则
 * 同时供 GEPA 评估早退与质疑智能体核对使用。短请求（{@code < skipLength}）
 * 或模型调用失败时返回空分解，不影响主流程。</p>
 *
 * <p>会话内缓存：避免在同一会话短时间内重复对相似请求做拆解。命中策略
 * 是请求文本完全相等（去首尾空白）；TTL 由代码侧的 {@link #ttlMillis} 控制。</p>
 */
public class GoalManager {

    private static final Logger log = LoggerFactory.getLogger(GoalManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REQUEST_LEN = 600;
    /** 缓存最多保留 16 条最近分解，过满时按 LRU 淘汰 */
    private static final int CACHE_CAPACITY = 16;

    private static final String SYS_PROMPT = GoalPrompts.SYS_PROMPT;

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    private final int skipLength;
    private final long ttlMillis;
    /** 用于上报真实 token 用量；null 时跳过统计（向后兼容） */
    private final TokenTracker tokenTracker;

    /** 会话级缓存（LRU 由 LinkedHashMap.removeEldestEntry 实现） */
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    public GoalManager(ChatModelBase model) {
        this(model, null, 50, 600_000L); // 默认 50 字阈值、TTL 10 分钟
    }

    public GoalManager(ChatModelBase model, TokenTracker tokenTracker) {
        this(model, tokenTracker, 50, 600_000L);
    }

    public GoalManager(ChatModelBase model, TokenTracker tokenTracker, int skipLength, long ttlMillis) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.skipLength = Math.max(0, skipLength);
        this.ttlMillis = Math.max(0, ttlMillis);
        this.generateOptions = GenerateOptions.builder().build();
    }

    /**
     * 分解用户请求为目标 + 结构化准则；命中缓存时直接返回。
     *
     * @param userInput 用户原始输入
     * @return 目标分解结果（失败时返回空结果，不抛异常）
     */
    public GoalDecomposition decompose(String userInput) {
        if (userInput == null || userInput.isBlank()
                || userInput.trim().length() < skipLength) {
            return empty(userInput);
        }
        String key = userInput.trim();
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null && (System.currentTimeMillis() - hit.timestamp) < ttlMillis) {
                log.info("目标分解缓存命中（{} 字）", key.length());
                return hit.result;
            }
        }
        try {
            String truncated = userInput.length() > MAX_REQUEST_LEN
                    ? userInput.substring(0, MAX_REQUEST_LEN) + "..." : userInput;

            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(SYS_PROMPT).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(truncated).build();

            StringBuilder responseText = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block();

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                responseText.append(tb.getText());
                            }
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("GoalManager", usage[0], usage[1]);
            }

            GoalDecomposition result = parse(userInput, responseText.toString().trim());
            synchronized (cache) {
                cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
            }
            return result;
        } catch (Exception e) {
            log.warn("目标分解失败，跳过: {}", e.getMessage());
            return empty(userInput);
        }
    }

    /** 清空会话缓存（切换会话或显式清理时调用） */
    public void clearCache() {
        synchronized (cache) { cache.clear(); }
    }

    private GoalDecomposition empty(String userInput) {
        return new GoalDecomposition(userInput, List.of(), "", List.of());
    }

    private GoalDecomposition parse(String originalRequest, String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) json = json.substring(start, end + 1);
            }
            JsonNode root = MAPPER.readTree(json);

            List<String> goals = new ArrayList<>();
            JsonNode goalsNode = root.get("goals");
            if (goalsNode != null && goalsNode.isArray()) {
                for (JsonNode g : goalsNode) {
                    if (g.isTextual() && !g.asText().isBlank()) goals.add(g.asText());
                }
            }
            String successCriteria = root.path("successCriteria").asText("");

            List<SuccessCriterion> criteria = new ArrayList<>();
            JsonNode critNode = root.get("criteria");
            if (critNode != null && critNode.isArray()) {
                for (JsonNode c : critNode) {
                    String type = c.path("type").asText("freeform");
                    String predicate = c.path("predicate").asText("");
                    if (!predicate.isBlank()) {
                        criteria.add(new SuccessCriterion(type, predicate));
                    }
                }
            }

            log.info("目标分解完成 — {} 个目标, {} 条核验点", goals.size(), criteria.size());
            return new GoalDecomposition(originalRequest, goals, successCriteria, criteria);
        } catch (Exception e) {
            log.warn("解析目标分解结果失败: {}", e.getMessage());
            return empty(originalRequest);
        }
    }

    /** 缓存项：分解结果 + 写入时间戳 */
    private record CacheEntry(GoalDecomposition result, long timestamp) {}
}
