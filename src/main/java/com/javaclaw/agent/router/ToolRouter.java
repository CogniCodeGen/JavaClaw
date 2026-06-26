package com.javaclaw.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.mcp.McpClient;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.prompt.RouterPrompts;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillManager;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具路由器 — 在对话执行前分析用户意图，返回本轮需要的工具组、技能和 MCP 服务器
 *
 * <p>通过单次轻量级模型调用实现快速路由，避免加载所有工具 schema 到上下文。
 * 路由失败时自动降级为全量加载（保持与改造前一致的行为）。</p>
 *
 * @author JavaClaw
 */
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 匹配 JSON 块的正则（支持 ```json 包裹和裸 JSON） */
    private static final Pattern JSON_PATTERN = Pattern.compile(
            "```json\\s*\\n?(\\{.*?})\\s*\\n?```|(?s)(\\{[^{}]*\"toolGroups\"[^{}]*})",
            Pattern.DOTALL
    );

    /** 简单问候模式 */
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(你好|hi|hello|嗨|hey|哈喽|早上好|下午好|晚上好|在吗|在不在|谢谢|感谢|好的|嗯|ok|行)$",
            Pattern.CASE_INSENSITIVE
    );

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    /** 用于上报路由模型调用的真实 token；null 时跳过统计 */
    private final TokenTracker tokenTracker;

    public ToolRouter(ChatModelBase model) {
        this(model, null);
    }

    public ToolRouter(ChatModelBase model, TokenTracker tokenTracker) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /**
     * 分析用户消息，返回路由结果
     *
     * <p>执行流程：
     * <ol>
     *   <li>快捷路径检测（问候语 → 无工具，配置禁用 → 全量）</li>
     *   <li>构建路由提示词（包含可用工具组、技能、MCP 描述）</li>
     *   <li>单次模型调用获取 JSON 路由结果</li>
     *   <li>解析 JSON，失败则降级为全量</li>
     * </ol>
     *
     * @param userMessage 用户输入的原始文本
     * @return 路由结果
     */
    public RoutingResult route(String userMessage) {
        // 快捷路径：简短问候语，无需工具
        if (isGreeting(userMessage)) {
            log.info("路由快捷判断 — 问候语，跳过工具加载");
            return RoutingResult.noTools();
        }

        try {
            String sysPrompt = buildRoutingPrompt();
            Msg sysMsg = Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .name("system")
                    .textContent(sysPrompt)
                    .build();
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .textContent(userMessage)
                    .build();

            // 单次模型调用，收集完整响应（15 秒硬超时：路由超时立即降级为全量，绝不阻塞主流程）
            StringBuilder responseText = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block(Duration.ofSeconds(15));

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb) {
                                responseText.append(tb.getText());
                            }
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("ToolRouter", usage[0], usage[1]);
            }

            String raw = responseText.toString().trim();
            log.debug("路由模型响应: {}", raw);
            return parseRoutingResult(raw);

        } catch (Exception e) {
            log.warn("工具路由失败，降级为全量加载: {}", e.getMessage());
            return RoutingResult.fallbackAll();
        }
    }

    /**
     * 判断是否为简单问候语
     */
    private boolean isGreeting(String message) {
        if (message == null) return false;
        String trimmed = message.trim();
        return trimmed.length() < 15 && GREETING_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 构建路由系统提示词
     */
    private String buildRoutingPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(RouterPrompts.ROUTING_PROMPT_HEADER);

        // 动态追加激活的技能列表（条件激活：platforms 按当前 OS 过滤；工具组判定路由期不可知，传 null 跳过）
        List<Skill> enabledSkills = SkillManager.getInstance().getActiveSkills(null);
        if (!enabledSkills.isEmpty()) {
            sb.append("\n## 可用技能\n\n");
            for (Skill skill : enabledSkills) {
                sb.append("- ").append(skill.getName());
                if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                    sb.append("：").append(skill.getDescription());
                }
                sb.append("\n");
            }
        }

        // 动态追加已启用的技能包列表（包优先：命中包时包内技能成组注入）
        if (com.javaclaw.config.AgentConfig.getInstance().isSkillBundlesEnabled()) {
            List<com.javaclaw.skill.SkillBundle> enabledBundles =
                    SkillManager.getInstance().getEnabledBundles();
            if (!enabledBundles.isEmpty()) {
                sb.append("\n## 可用技能包\n\n");
                for (com.javaclaw.skill.SkillBundle bundle : enabledBundles) {
                    sb.append("- ").append(bundle.name);
                    if (bundle.description != null && !bundle.description.isBlank()) {
                        sb.append("：").append(bundle.description);
                    }
                    sb.append("（含：").append(String.join("、", bundle.skills)).append("）\n");
                }
            }
        }

        // 动态追加 MCP 服务器列表（只列名称和工具数量，不列详情）
        // 注：MCP 信息通过 mcpServerSummaries 注入，避免 ToolRouter 直接依赖 McpClientManager
        // 如果有 MCP 服务器信息，会在 buildRoutingPrompt 外部追加

        sb.append(RouterPrompts.ROUTING_PROMPT_FOOTER);

        return sb.toString();
    }

    /**
     * 解析模型返回的 JSON 路由结果
     */
    private RoutingResult parseRoutingResult(String raw) {
        try {
            // 尝试提取 JSON
            String json = extractJson(raw);
            if (json == null) {
                log.warn("路由响应中未找到有效 JSON，降级为全量: {}", raw);
                return RoutingResult.fallbackAll();
            }

            JsonNode root = MAPPER.readTree(json);

            List<String> toolGroups = parseStringList(root, "toolGroups");
            List<String> skillNames = parseStringList(root, "skillNames");
            List<String> bundleNames = parseStringList(root, "bundleNames");
            List<String> mcpServers = parseStringList(root, "mcpServers");

            // 过滤无效的工具组名
            List<String> validGroups = toolGroups.stream()
                    .filter(RoutingResult.ALL_TOOL_GROUPS::contains)
                    .toList();

            RoutingResult result = new RoutingResult(validGroups, skillNames, bundleNames, mcpServers);
            log.info("路由结果 — 工具组: {}, 技能: {}, 技能包: {}, MCP: {}",
                    validGroups, skillNames, bundleNames, mcpServers);
            return result;

        } catch (Exception e) {
            log.warn("解析路由结果失败，降级为全量: {}", e.getMessage());
            return RoutingResult.fallbackAll();
        }
    }

    /**
     * 从模型响应中提取 JSON 字符串
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // 尝试直接解析（模型可能返回纯 JSON）
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // 尝试正则提取
        Matcher matcher = JSON_PATTERN.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }

        return null;
    }

    /**
     * 从 JsonNode 中解析字符串数组
     */
    private List<String> parseStringList(JsonNode root, String field) {
        List<String> result = new ArrayList<>();
        JsonNode node = root.get(field);
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }
}
