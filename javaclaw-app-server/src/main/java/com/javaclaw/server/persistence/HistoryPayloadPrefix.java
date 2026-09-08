package com.javaclaw.server.persistence;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/** 只扫描数据库已限制长度的前缀，按根字段提取展示信息，不能把嵌套字段当成权威状态。 */
final class HistoryPayloadPrefix {
    private static final JsonFactory FACTORY = new JsonFactory();
    private static final Set<String> FIELDS = Set.of(
            "callId",
            "toolName",
            "producerId",
            "toolRevision",
            "code",
            "message",
            "state",
            "reason",
            "risk",
            "operation",
            "relativePath",
            "exitCode",
            "commandId",
            "workingDirectory",
            "argv",
            "output",
            "prompt",
            "answered",
            "childThreadId",
            "strategy",
            "consumedTokens",
            "summary",
            "errorCode",
            "operationId",
            "sessionId",
            "complete",
            "failureCode",
            "manager",
            "command",
            "changes");

    private HistoryPayloadPrefix() {}

    static Map<String, String> fields(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        try (JsonParser parser = FACTORY.createParser(prefix)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return result;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                int fieldStart = (int) parser.currentTokenLocation().getCharOffset();
                JsonToken token = parser.nextToken();
                if (FIELDS.contains(name)) {
                    readValue(parser, prefix, name, fieldStart, token, result);
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException incompletePrefix) {
            // CLOB 可能在字符串或嵌套对象中被截断；保留已经验证属于根对象的字段，不尝试猜测后续结构。
        }
        return result;
    }

    private static void readValue(
            JsonParser parser, String prefix, String name, int start, JsonToken token, Map<String, String> result)
            throws IOException {
        if (token == JsonToken.VALUE_STRING) {
            result.put(
                    name,
                    HistoryMessagePrefix.text(prefix.substring(start), name, 4096)
                            .value());
            parser.getText();
        } else if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            int valueStart = (int) parser.currentTokenLocation().getCharOffset();
            // 先保留有界片段；skipChildren 在不完整前缀上失败时仍能展示已经存在的执行输出。
            result.put(name, prefix.substring(valueStart, Math.min(prefix.length(), valueStart + 4096)));
            parser.skipChildren();
            int end = (int) parser.currentLocation().getCharOffset();
            result.put(name, prefix.substring(valueStart, Math.min(end, valueStart + 4096)));
        } else if (token != null && token.isScalarValue() && token != JsonToken.VALUE_NULL) {
            result.put(name, parser.getText());
        }
    }
}
