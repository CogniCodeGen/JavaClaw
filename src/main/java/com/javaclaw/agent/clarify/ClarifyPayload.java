package com.javaclaw.agent.clarify;

/**
 * 澄清请求载荷
 *
 * <p>通过 {@code ConversationEvent.Custom("clarify_request", payload)} 投递给 UI，
 * 让聊天界面把模型主动发起的澄清请求渲染为一张突出显示的卡片。</p>
 *
 * @param reason   模型向用户解释为什么需要澄清（卡在哪里、为什么模型不能替决策）
 * @param question 向用户的具体提问（应当具体可回答）
 */
public record ClarifyPayload(String reason, String question) {}
