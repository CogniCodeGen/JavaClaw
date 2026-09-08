package com.javaclaw.server.persistence;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.MessageRole;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolException;

/** 对规范 JSON 的有界前缀提取消息摘要，不将大型 CLOB 正文全部物化。 */
final class HistoryMessagePrefix {
    private HistoryMessagePrefix() {}

    static Text text(String prefix, String field, int limit) {
        int position = prefix.indexOf("\"" + field + "\":");
        if (position < 0) {
            return new Text("", true);
        }
        position += field.length() + 3;
        while (position < prefix.length() && Character.isWhitespace(prefix.charAt(position))) {
            position++;
        }
        if (position == prefix.length() || prefix.charAt(position++) != '"') {
            return new Text("", true);
        }
        return read(prefix, position, limit);
    }

    private static Text read(String prefix, int position, int limit) {
        StringBuilder value = new StringBuilder();
        boolean complete = false;
        while (position < prefix.length() && value.length() <= limit) {
            char next = prefix.charAt(position++);
            if (next == '"') {
                complete = true;
                break;
            }
            if (next == '\\') {
                Escaped escaped = escaped(prefix, position);
                if (!escaped.complete()) {
                    break;
                }
                next = escaped.value();
                position = escaped.position();
            }
            value.append(next);
        }
        if (value.length() > limit) {
            value.setLength(limit);
        }
        if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
            value.setLength(value.length() - 1);
        }
        return new Text(value.toString(), !complete);
    }

    private static Escaped escaped(String prefix, int position) {
        if (position >= prefix.length()) {
            return new Escaped(' ', position, false);
        }
        char value = prefix.charAt(position++);
        if (value != 'u') {
            return new Escaped(unescape(value), position, true);
        }
        if (position + 4 > prefix.length()) {
            return new Escaped(' ', position, false);
        }
        char decoded = (char) Integer.parseInt(prefix.substring(position, position + 4), 16);
        return new Escaped(decoded, position + 4, true);
    }

    static Optional<MessageRole> role(String prefix) {
        Text role = text(prefix, "role", 30);
        return role.truncated() ? Optional.empty() : Optional.of(MessageRole.valueOf(role.value()));
    }

    static List<AttachmentRef> attachments(String prefix, CanonicalJson json) {
        int start = prefix.indexOf("\"attachments\":[");
        if (start < 0) {
            return List.of();
        }
        start += "\"attachments\":".length();
        boolean quoted = false;
        boolean escape = false;
        for (int position = start + 1; position < prefix.length(); position++) {
            char value = prefix.charAt(position);
            if (escape) {
                escape = false;
            } else if (quoted && value == '\\') {
                escape = true;
            } else if (value == '"') {
                quoted = !quoted;
            } else if (!quoted && value == ']') {
                return decodeAttachments(prefix.substring(start, position + 1), json);
            }
        }
        return List.of();
    }

    private static List<AttachmentRef> decodeAttachments(String value, CanonicalJson json) {
        try {
            return json.decode(json.parse("{\"values\":" + value + "}"), AttachmentList.class).values().stream()
                    .limit(32)
                    .toList();
        } catch (IllegalArgumentException | ProtocolException invalid) {
            // 来源已经落盘，解码失败属于持久数据损坏，不能误报为客户端请求格式错误。
            throw new PersistenceException("持久消息附件数据损坏", invalid);
        }
    }

    private static char unescape(char value) {
        return switch (value) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> value;
        };
    }

    private record AttachmentList(List<AttachmentRef> values) {}

    private record Escaped(char value, int position, boolean complete) {}

    record Text(String value, boolean truncated) {}
}
