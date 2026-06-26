package com.javaclaw.agent.expert;

import com.javaclaw.agent.memory.MemoryManager;
import com.javaclaw.agent.model.ModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态任务智能体工具 — 根据任务描述智能生成提示词并创建专用智能体执行
 *
 * <p>编排器调用此工具时，会：</p>
 * <ol>
 *   <li>根据任务描述和对话上下文动态生成针对性的系统提示词</li>
 *   <li>根据所需能力从工具注册表中组合工具集</li>
 *   <li>创建临时任务智能体（用后即弃）</li>
 *   <li>执行任务并通过结构化 API 提取纯文本结果</li>
 * </ol>
 *
 * @author JavaClaw
 */
public class DynamicTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskTool.class);

    /** 支持的能力枚举 */
    public enum Capability {
        WEB("web", "网页浏览：可以导航网页、获取页面快照、点击元素、填写表单、截图等", 8),
        EMAIL("email", "邮件操作：可以发送邮件、查看收件箱、搜索邮件、回复邮件等", 5),
        SYSTEM("system", "系统操作：可以获取系统信息、截图、操控鼠标键盘、管理文件等", 6),
        NOTIFICATION("notification", "消息通知：可以通过钉钉、企业微信、飞书、邮件、Webhook 发送通知", 5),
        COMMAND("command", "命令行执行：可以执行 Shell 命令、编译构建、运行脚本、查看进程等（不含文件操作）", 8);

        public final String key;
        public final String description;
        public final int maxIters;

        Capability(String key, String description, int maxIters) {
            this.key = key;
            this.description = description;
            this.maxIters = maxIters;
        }

        /** 按 key 查找，找不到返回 null */
        public static Capability fromKey(String key) {
            for (Capability c : values()) {
                if (c.key.equals(key)) return c;
            }
            return null;
        }
    }

    private final ModelFactory modelFactory;
    private final MemoryManager memoryManager;
    private final Map<String, Object> capabilityTools;

    /**
     * @param modelFactory    模型工厂
     * @param memoryManager   记忆管理器
     * @param capabilityTools 能力 key → 工具实例映射
     */
    public DynamicTaskTool(ModelFactory modelFactory, MemoryManager memoryManager,
                           Map<String, Object> capabilityTools) {
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.capabilityTools = capabilityTools;
        log.info("动态任务工具已创建，可用能力: {}", capabilityTools.keySet());
    }

    /**
     * 动态创建专用任务智能体执行特定任务
     */
    @Tool(name = "execute_task_agent",
            description = "动态创建专用任务智能体来执行特定子任务。" +
                    "根据任务描述、所需能力和上下文，自动生成针对性提示词并创建智能体执行。" +
                    "适用于规划拆分后的子任务执行，每个子任务都会获得定制化的智能体。")
    public String executeTaskAgent(
            @ToolParam(name = "task", description = "具体任务描述，应清晰明确") String task,
            @ToolParam(name = "capabilities",
                    description = "所需能力列表，逗号分隔：web/email/system/notification/command/none") String capabilities,
            @ToolParam(name = "context",
                    description = "与此任务相关的上下文信息，从对话历史中提取") String context) {

        log.info("动态任务创建 — 任务: {}, 能力: {}", task, capabilities);

        try {
            // 1. 解析能力列表（结构化枚举，非字符串操作）
            List<Capability> caps = parseCapabilities(capabilities);

            // 2. 生成任务专用系统提示词
            String sysPrompt = buildTaskPrompt(task, caps, context);

            // 3. 根据能力组合工具集
            Toolkit toolkit = buildToolkit(caps);

            // 4. 确定最大迭代次数
            int maxIters = caps.isEmpty() ? 1 : caps.stream()
                    .mapToInt(c -> c.maxIters)
                    .max().orElse(1);

            // 5. 创建临时任务智能体
            ReActAgent.Builder agentBuilder = ReActAgent.builder()
                    .name("任务智能体")
                    .sysPrompt(sysPrompt)
                    .model(modelFactory.createChatModel())
                    .maxIters(maxIters);

            if (toolkit != null) {
                agentBuilder.toolkit(toolkit);
            }

            ReActAgent agent = agentBuilder.build();
            log.info("任务智能体已创建 — maxIters: {}, 工具集: {}",
                    maxIters, toolkit != null ? "已配置" : "无");

            // 6. 构建任务消息并执行
            Msg taskMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .name("orchestrator")
                    .textContent(task)
                    .build();

            Msg result = agent.call(List.of(taskMsg)).block();

            // 7. 提取结果（含工具调用摘要，让编排器可见执行过程）
            String resultText = extractResultWithToolSummary(result);
            if (resultText.isBlank()) {
                resultText = "任务执行完成但无文本返回";
            }

            log.info("任务智能体执行完成 — 结果长度: {} 字符", resultText.length());
            return resultText;

        } catch (Exception e) {
            log.error("动态任务执行失败 — 任务: {}", task, e);
            return "任务执行失败: " + e.getMessage();
        }
    }

    // ==================== 能力解析（枚举驱动） ====================

    /**
     * 将逗号分隔的能力字符串解析为枚举列表
     */
    private List<Capability> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()
                || "none".equalsIgnoreCase(capabilities.trim())) {
            return List.of();
        }

        List<Capability> result = new ArrayList<>();
        for (String part : capabilities.split(",")) {
            String key = part.trim().toLowerCase();
            Capability cap = Capability.fromKey(key);
            if (cap != null) {
                result.add(cap);
            } else {
                log.warn("未知能力: {}，已忽略", key);
            }
        }
        return result;
    }

    // ==================== 提示词构建 ====================

    /**
     * 根据任务和上下文生成针对性的系统提示词
     */
    private String buildTaskPrompt(String task, List<Capability> caps, String context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个专注于特定任务的智能体。你的唯一目标是高质量地完成以下任务。\n\n");

        prompt.append("## 你的任务\n");
        prompt.append(task).append("\n\n");

        if (!caps.isEmpty()) {
            prompt.append("## 你的能力\n");
            for (Capability cap : caps) {
                prompt.append("- ").append(cap.description).append("\n");
            }
            prompt.append("\n");
        }

        if (context != null && !context.isBlank()) {
            prompt.append("## 背景上下文\n");
            prompt.append(context).append("\n\n");
        }

        prompt.append("## 输出规则（必须遵循）\n");
        prompt.append("- 只输出最终结果和结论，不要复述工具调用的过程和中间输出\n");
        prompt.append("- 不要在回复中包含工具名称、状态标记（如 [成功]、[失败]）或 XML 标签\n");
        prompt.append("- 用中文回复，结构清晰（可使用标题、列表、编号）\n");
        prompt.append("- 完成任务后直接给出结果，不需要额外的总结性开头\n");

        return prompt.toString();
    }

    // ==================== 工具集构建 ====================

    /**
     * 根据能力列表组合工具集
     */
    private Toolkit buildToolkit(List<Capability> caps) {
        if (caps.isEmpty()) {
            return null;
        }

        Toolkit toolkit = new Toolkit();
        boolean hasTools = false;

        for (Capability cap : caps) {
            Object tools = capabilityTools.get(cap.key);
            if (tools != null) {
                toolkit.registerTool(tools);
                hasTools = true;
            }
        }

        return hasTools ? toolkit : null;
    }

    // ==================== 结构化结果提取 ====================

    /**
     * 从智能体返回的 Msg 中结构化提取纯文本内容
     *
     * <p>只提取 {@link TextBlock}，自动排除 ToolUseBlock、ToolResultBlock 等
     * 非用户可读的结构化内容块。无需正则清洗。</p>
     *
     * @param msg 智能体返回的消息
     * @return 纯文本结果
     */
    private static String extractResultWithToolSummary(Msg msg) {
        if (msg == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 1. 提取工具调用摘要（ToolUseBlock），让编排器可见执行过程
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolCalls.isEmpty()) {
            sb.append("【执行过程】\n");
            for (ToolUseBlock tool : toolCalls) {
                sb.append("- 调用工具: ").append(tool.getName()).append("\n");
            }
            sb.append("\n");
        }

        // 2. 提取文本结果（TextBlock）
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            if (!toolCalls.isEmpty()) {
                sb.append("【执行结果】\n");
            }
            sb.append(textBlocks.stream()
                    .map(TextBlock::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n")));
        }

        return sb.toString();
    }
}
