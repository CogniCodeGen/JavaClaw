package com.javaclaw.prompt;

import java.util.Collection;

/**
 * 规划（研讨）模式提示词集中管理 —— 协调者系统提示词的动态拼接、专家选择重试、
 * 协作讨论公告等发给模型的文本。
 *
 * <p>协调者基础人设仍在 {@link AgentPrompts#PLAN_COORDINATOR_SYS_PROMPT}，本类负责把
 * 运行时的可选专家清单等动态信息拼接进去。</p>
 */
public final class PlanModePrompts {

    private PlanModePrompts() {}

    /** 协调者系统提示词 = 基础人设 + 当前可选专家清单（约束 experts 取值） */
    public static String coordinatorSysPrompt(Collection<String> expertNames) {
        return AgentPrompts.PLAN_COORDINATOR_SYS_PROMPT
                + "\n\n## 当前可选专家\n\n"
                + String.join("、", expertNames)
                + "\n\n上述名称为唯一合法取值；输出的 experts 数组元素必须与此处某一项逐字一致。";
    }

    /** 首轮专家选择 JSON 解析失败时的修正重试提示 */
    public static String expertSelectionRetry(Collection<String> expertNames) {
        return "你的回复中未包含有效的专家选择 JSON。请严格按以下格式输出：\n"
                + "```json\n{\"experts\": [\"专家名称1\", \"专家名称2\"], \"topic\": \"讨论主题\"}\n```\n"
                + "可选专家：" + String.join(", ", expertNames);
    }

    /** 协作讨论公告：标注讨论主题（追加到用户原始需求后） */
    public static String announcementTopic(String topic) {
        return "\n\n[协作讨论] 主题：" + topic;
    }

    /** 协作讨论公告：要求各专家从自身视角给出见解的统一尾注 */
    public static final String ANNOUNCEMENT_PERSPECTIVE_SUFFIX =
            "\n\n请从你自己的专业视角给出具体见解，避免复述他人或预设方案。";
}
