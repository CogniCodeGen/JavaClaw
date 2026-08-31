package com.javaclaw.desktop;

import java.util.List;
import java.util.Objects;

/**
 * 主窗口处理进度的安全展示投影；只包含已持久化状态和用户可见摘要，不包含隐藏推理或原始 JSON。
 *
 * @param phase 当前阶段的用户可读名称
 * @param status 当前 Turn 状态说明
 * @param entries 最近工具、文件、MCP、子任务与交互摘要
 * @param usage 当前 Thread 的服务端累计 Token；服务端尚未报告时为 unavailable
 * @param active 是否存在非终态 Turn
 */
record DesktopProgressSnapshot(String phase, String status, List<Entry> entries, TokenUsage usage, boolean active) {
    DesktopProgressSnapshot {
        phase = Objects.toString(phase, "等待任务");
        status = Objects.toString(status, "尚未开始");
        entries = List.copyOf(entries);
        Objects.requireNonNull(usage, "usage");
    }

    static DesktopProgressSnapshot empty() {
        return new DesktopProgressSnapshot("等待任务", "选择会话后显示处理阶段", List.of(), TokenUsage.unavailable(), false);
    }

    /**
     * 一条执行摘要。
     *
     * @param kind 稳定的展示类别
     * @param title 简短标题
     * @param detail 有界用户可见摘要
     * @param state 已翻译的持久状态
     */
    record Entry(Kind kind, String title, String detail, String state) {
        Entry {
            Objects.requireNonNull(kind, "kind");
            title = Objects.toString(title, "处理步骤");
            detail = Objects.toString(detail, "");
            state = Objects.toString(state, "未知");
        }
    }

    /** 进度摘要类别，用于图标和可访问名称，不决定工具权限。 */
    enum Kind {
        TOOL,
        FILE,
        MCP,
        SUBTASK,
        PLAN,
        ARTIFACT,
        INTERACTION,
        ERROR,
        OTHER
    }

    /**
     * 服务端累计的 Provider usage；预算预留和文本估算不得进入此投影。费用没有可靠价格来源，因此界面始终单独显示“—”。
     *
     * @param available 服务端是否已报告 usage/updated
     * @param inputTokens 输入 Token
     * @param outputTokens 输出 Token
     * @param reasoningTokens 推理 Token
     */
    record TokenUsage(boolean available, long inputTokens, long outputTokens, long reasoningTokens) {
        TokenUsage {
            if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0) {
                throw new IllegalArgumentException("token usage must be non-negative");
            }
        }

        static TokenUsage unavailable() {
            return new TokenUsage(false, 0, 0, 0);
        }
    }
}
