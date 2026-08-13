package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.core.ToolInputRequiredException;
import com.javaclaw.framework.spi.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Framework-owned host tool that durably suspends a Run for explicit user clarification. */
@ToolContract(group = "agents", permissions = {"interaction.request"}, idempotent = false)
public final class ClarifyTools {

    private static final Logger log = LoggerFactory.getLogger(ClarifyTools.class);

    @Tool(name = "ask_user_clarification",
            description = "【中断本轮，询问用户】当且仅当用户输入存在你无法替其决策的歧义或重要缺失时调用本工具，" +
                    "向用户提问让其修正，并主动停止本轮回复。" +
                    "适用场景示例：" +
                    "① 用户说\"帮我把文件转码\"但未指定源/目标编码；" +
                    "② 用户说\"删除旧版本\"但未指定版本号或路径；" +
                    "③ 多个候选方案在用户偏好维度（性能 vs 体积、安全 vs 易用）必须由人来选；" +
                    "④ 任务涉及用户私有信息（账号、邮箱、收件人）但用户未提供。" +
                    "【严格约束】" +
                    "1. 只在确实无法基于上下文与常识推断时调用；能用合理默认值时绝不调用。" +
                    "2. 调用本工具后你必须立即结束本轮回复，不要再做任何推理、生成或工具调用，等待用户的下一条输入。" +
                    "3. reason 字段必须用中文向用户说明你为什么问这个问题（识别出的歧义/缺失是什么、为什么模型不能替决策）。" +
                    "4. question 字段必须是具体可回答的问题；若有候选项请列出供用户选择，避免开放式提问。")
    public String askUserClarification(
            @ToolParam(
                    description = "向用户解释为什么需要澄清：识别出的歧义/缺失是什么、为什么模型不能替决策。中文。") String reason,
            @ToolParam(
                    description = "向用户的具体提问；若有候选选项请逐条列出。中文。") String question) {
        String safeReason = reason == null ? "" : reason.trim();
        String safeQuestion = question == null ? "" : question.trim();
        if (safeReason.isEmpty() && safeQuestion.isEmpty()) {
            return "[ask_user_clarification][失败] reason 与 question 均为空，"
                    + "澄清请求无效。请重新组织你的澄清问题。";
        }

        log.info("模型主动请求澄清: reason=「{}」 question=「{}」", safeReason, safeQuestion);
        var context = JsonNodeFactory.instance.objectNode();
        context.put("kind", "clarify_request");
        context.putObject("payload")
                .put("reason", safeReason)
                .put("question", safeQuestion);
        throw new ToolInputRequiredException(context,
                safeReason.isBlank() ? "Agent 请求用户澄清" : safeReason);
    }
}
