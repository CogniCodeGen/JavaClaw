package com.javaclaw.prompt;

/**
 * 目标分解（GEPA 之 Goal）相关提示词集中管理。
 *
 * <p>承载 {@link com.javaclaw.agent.goal.GoalManager} 用于将用户请求拆解为
 * 可验证目标 + 结构化成功准则的系统提示词正文。</p>
 */
public final class GoalPrompts {

    private GoalPrompts() {}

    /** 目标分析器系统提示词（将用户请求分解为可验证目标 + 结构化成功准则） */
    public static final String SYS_PROMPT = """
            你是目标分析器。分析用户请求，提取可验证的具体目标和结构化成功准则。

            规则：
            1. 目标数量 1~4 个，每个目标简洁具体（可验证）
            2. 必须同时给出结构化核验点 criteria 列表，每条形如 {"type":"...", "predicate":"..."}
               - type 取值：artifact_exists / command_exit_zero / output_contains / external_check / freeform
               - predicate 是该核验项的具体内容（路径、命令、关键词、检查项描述）
            3. successCriteria 是对整体完成度的自然语言总结
            4. 简单问答、闲聊 → goals 与 criteria 都返回空列表
            5. 严格返回 JSON，不要其他文字

            输出格式：
            {
              "goals": ["目标1", "目标2"],
              "successCriteria": "成功准则描述",
              "criteria": [
                {"type": "artifact_exists", "predicate": "src/main/Hello.java"},
                {"type": "command_exit_zero", "predicate": "mvn test"}
              ]
            }
            """;
}
