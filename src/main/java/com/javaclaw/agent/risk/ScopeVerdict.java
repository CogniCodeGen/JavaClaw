package com.javaclaw.agent.risk;

import java.util.List;

/**
 * 风险评估智能体对一次高风险工具操作的"影响范围"判定结果。
 *
 * @param withinScope    智能体判定操作的副作用是否完全限定在任务工作目录及其子目录内
 * @param affectedPaths  智能体识别出的、该操作会写入/修改/删除的文件或目录路径
 *                       （尽量为绝对路径，相对路径相对工作目录解析）；命令仅在工作目录内运行
 *                       而无显式路径时可为空列表
 * @param reason         简短中文判定理由（供日志与 Toast 展示）
 */
public record ScopeVerdict(boolean withinScope, List<String> affectedPaths, String reason) {

    public ScopeVerdict {
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
        reason = reason == null ? "" : reason;
    }

    /** 构造一个"超出范围/无法判定"的保守结果（必走人工确认）。 */
    public static ScopeVerdict outOfScope(String reason) {
        return new ScopeVerdict(false, List.of(), reason);
    }
}
