package com.javaclaw.agent.expert;

import com.javaclaw.agent.TokenTracker;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 智能体提示词优化器 — 根据用户给出的智能体名称、描述和草稿，
 * 调用模型生成完整、可直接使用的系统提示词。
 *
 * <p>用于「智能体设置」面板中的 AI 智能补全按钮：用户只需写一句话描述意图，
 * 即可获得专业结构化的 system prompt，免去手动撰写的成本。</p>
 *
 * <p>同步阻塞调用，调用方应包装在异步线程内。失败时返回 {@code null}，调用方负责回退。</p>
 *
 * @author JavaClaw
 */
public class AgentPromptOptimizer {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptOptimizer.class);

    private static final String SYS_PROMPT = """
            你是「智能体提示词工程师」。基于用户给出的智能体名称、用途描述与现有提示词草稿，
            生成一份完整、专业、可直接使用的系统提示词（system prompt），用于驱动一个 ReAct 风格的子智能体。

            生成原则：
            1. 角色定位：在开头一两句话内明确该智能体的身份、擅长领域和典型使用场景
            2. 能力清单：列出该智能体能解决的具体任务（3~6 条），逐条简短具体
            3. 工作流程：在需要时给出推荐的处理步骤或思考框架
            4. 行为准则：约束输出风格、格式、边界（避免越权、避免幻觉、不擅长的事直接说明）
            5. 中文输出：所有内容均使用中文，专业、简洁、避免空话套话
            6. 不要使用 Markdown 一级/二级标题（# / ##），用空行和短标签划分段落即可
            7. 不要包裹任何解释、不要加引号、不要使用代码块围栏，直接输出可粘贴的提示词正文

            若用户描述非常简略，请结合智能体名称合理推断其用途并主动扩展；
            若用户已提供较完整的草稿，则在保留其核心意图的基础上做润色与结构化补全。
            """;

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    /** 提示词优化模型调用的 token 用量上报；null 时跳过统计 */
    private final TokenTracker tokenTracker;

    public AgentPromptOptimizer(ChatModelBase model) {
        this(model, null);
    }

    public AgentPromptOptimizer(ChatModelBase model, TokenTracker tokenTracker) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /**
     * 根据用户输入生成或优化系统提示词。
     *
     * @param agentName   智能体名称（必填）
     * @param description 智能体描述/用途（可空）
     * @param draftPrompt 现有提示词草稿（可空，非空时作为优化基线）
     * @return 优化后的完整提示词；失败或空响应时返回 {@code null}
     */
    public String optimize(String agentName, String description, String draftPrompt) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }

        StringBuilder userInput = new StringBuilder();
        userInput.append("智能体名称：").append(agentName.trim()).append("\n");
        if (description != null && !description.isBlank()) {
            userInput.append("用途描述：").append(description.trim()).append("\n");
        }
        if (draftPrompt != null && !draftPrompt.isBlank()) {
            userInput.append("现有提示词草稿：\n").append(draftPrompt.trim()).append("\n");
        }
        userInput.append("\n请基于以上信息生成完整的系统提示词。");

        try {
            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system")
                    .textContent(SYS_PROMPT).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user")
                    .textContent(userInput.toString()).build();

            StringBuilder result = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block();

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() == null) continue;
                    for (ContentBlock block : resp.getContent()) {
                        if (block instanceof TextBlock tb && tb.getText() != null) {
                            result.append(tb.getText());
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("AgentPromptOptimizer", usage[0], usage[1]);
            }

            String text = stripCodeFence(result.toString().trim());
            if (text.isEmpty()) {
                log.warn("智能体提示词优化返回空内容");
                return null;
            }
            log.info("智能体提示词优化完成，{} 字", text.length());
            return text;
        } catch (Exception e) {
            log.warn("智能体提示词优化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 去除可能的 markdown 代码块包裹 */
    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        int lastFence = t.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return t.substring(firstNewline + 1, lastFence).trim();
        }
        return t;
    }
}
