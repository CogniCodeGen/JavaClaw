package com.javaclaw.api.conversation;

/**
 * 模式的能力声明。
 *
 * <p>UI 层据此决定是否显示附件入口、是否允许用户取消流等。不同模式按需声明。</p>
 *
 * @param supportsAttachments 是否支持附件输入（图片 / 文档 / PDF）
 * @param supportsStreaming   是否支持流式输出（边生成边显示）
 * @param supportsCancel      是否支持用户中途取消
 * @param requiresKnowledge   是否依赖知识库（RAG），为 true 时 UI 可提供知识库菜单
 * @param requiresBrowser     是否依赖浏览器（Playwright）
 */
public record Capabilities(
        boolean supportsAttachments,
        boolean supportsStreaming,
        boolean supportsCancel,
        boolean requiresKnowledge,
        boolean requiresBrowser
) {
    /** 默认能力集：支持附件、流式、取消、知识库、浏览器 */
    public static Capabilities defaults() {
        return new Capabilities(true, true, true, true, true);
    }

    /** 最小能力集：什么都不支持（适合动作型模式或占位） */
    public static Capabilities minimal() {
        return new Capabilities(false, false, false, false, false);
    }
}
