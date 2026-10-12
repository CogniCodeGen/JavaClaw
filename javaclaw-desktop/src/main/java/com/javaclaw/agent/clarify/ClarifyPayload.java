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
public record ClarifyPayload(String reason, String question) {
    public com.fasterxml.jackson.databind.JsonNode toJson() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("reason", reason == null ? "" : reason)
                .put("question", question == null ? "" : question);
    }

    public static java.util.Optional<ClarifyPayload> fromJson(
            com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isObject()) return java.util.Optional.empty();
        String reason = value.path("reason").asText("");
        String question = value.path("question").asText("");
        if (reason.isBlank() && question.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.of(new ClarifyPayload(reason, question));
    }
}
