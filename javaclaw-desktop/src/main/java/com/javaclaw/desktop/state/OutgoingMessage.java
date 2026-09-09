package com.javaclaw.desktop.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolException;

/**
 * 当前对话尚未被权威历史替换的用户发送回显；只属于展示，不伪造 Item 或持久化序号。
 *
 * @param id 本地发送身份，使用带前缀的命令幂等键，不可空
 * @param text 原始发送正文，不可空
 * @param turnId 服务端已确认的 Turn 身份，未收到回执时为空
 * @param status 发送确认状态，不可空；网络错误可能发生在服务端提交之后
 * @param attempt 当前页面主动提交的递增尝试号，非负、无单位；重试可改变该值而继续复用命令身份
 */
public record OutgoingMessage(String id, String text, Optional<TurnId> turnId, Status status, long attempt) {
    /**
     * 创建未指定主动提交尝试号的兼容回显。
     *
     * @param id 本地发送身份，不可空
     * @param text 发送正文，不可空
     * @param turnId 权威 Turn 身份，未确认时为空
     * @param status 发送确认状态，不可空
     */
    public OutgoingMessage(String id, String text, Optional<TurnId> turnId, Status status) {
        this(id, text, turnId, status, 0);
    }

    /** 校验展示身份与确认状态；已接受消息必须绑定权威 Turn。 */
    public OutgoingMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        turnId = Objects.requireNonNull(turnId, "turnId");
        status = Objects.requireNonNull(status, "status");
        if (id.isBlank() || attempt < 0 || status == Status.ACCEPTED && turnId.isEmpty()) {
            throw new IllegalArgumentException("发送回显身份或确认状态无效");
        }
    }

    boolean committed(List<ItemEnvelope> items, List<ItemHistoryEntry> history) {
        if (turnId.isEmpty()) {
            return false;
        }
        TurnId accepted = turnId.orElseThrow();
        return history.stream()
                        .anyMatch(entry -> entry.turnId().equals(accepted)
                                && entry.role().filter(MessageRole.USER::equals).isPresent())
                || items.stream().anyMatch(item -> userMessage(item, accepted));
    }

    private static boolean userMessage(ItemEnvelope item, TurnId accepted) {
        if (!item.turnId().equals(accepted) || !CoreSchemas.MESSAGE.equals(item.schemaId())) {
            return false;
        }
        try {
            return LegacyCodec.JSON
                            .decode(item.payload(), CorePayloads.Message.class)
                            .role()
                    == MessageRole.USER;
        } catch (ProtocolException invalid) {
            // 无法识别的正文仍交给既有降级展示；不能凭损坏的载荷确认用户消息已经入库。
            return false;
        }
    }

    private static final class LegacyCodec {
        private static final CanonicalJson JSON = new CanonicalJson();
    }

    /** 本地回显与服务端确认之间的状态，不代表模型已经开始或完成回复。 */
    public enum Status {
        /** 命令正在提交或等待回执。 */
        SENDING,
        /** 已收到 Turn 回执，等待权威历史替换。 */
        ACCEPTED,
        /** 提交未得到确认；保留草稿并复用同一次发送身份重试。 */
        UNCONFIRMED
    }
}
