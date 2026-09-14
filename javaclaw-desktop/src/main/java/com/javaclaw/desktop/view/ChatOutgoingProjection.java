package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;

import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.protocol.CanonicalJson;

/** 本地发送卡片的有界页面数据；正文仅作纯文本，幂等身份不能进入页面。 */
final class ChatOutgoingProjection {
    private ChatOutgoingProjection() {}

    static Map<String, Object> values(OutgoingMessage message, CanonicalJson json) {
        var presented = TranscriptPresenter.presentOutgoing(message);
        String text = presented.body();
        int limit = Math.min(65_536, text.length());
        if (limit < text.length() && Character.isLowSurrogate(text.charAt(limit))) {
            limit--;
        }
        return Map.of(
                "id", id(message, json),
                "title", presented.title(),
                "style", presented.styleClass(),
                "text", text.substring(0, limit) + (limit < text.length() ? "\n[消息较长；确认后可查看完整消息]" : ""),
                "html", "",
                "references", List.of(),
                "streaming", false,
                "outgoing", message.status().name(),
                "sendAttempt", message.attempt());
    }

    static String id(OutgoingMessage message, CanonicalJson json) {
        // Web 只接触不可逆的展示身份；幂等键保留在当前投影的宿主动作表中。
        return "send:" + json.encode(Map.of("send", message.id())).sha256();
    }
}
