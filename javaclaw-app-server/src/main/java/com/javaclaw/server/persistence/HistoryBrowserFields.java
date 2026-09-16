package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

/** 流式跳过网页正文，仅保留精确路径上的少量浏览器附件证据；不物化大型 CLOB 或页面字段。 */
final class HistoryBrowserFields {
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                    StreamReadConstraints.builder().maxStringLength(16_384).build())
            .build();
    private static final Set<String> FIELDS = Set.of(
            "callId",
            "success",
            "output.version",
            "output.attachment.digest",
            "output.attachment.fileName",
            "output.attachment.mediaType",
            "output.attachment.sizeBytes",
            "output.observation.session.owner.workspaceId",
            "output.observation.session.owner.threadId",
            "output.observation.artifact.file.fileName",
            "output.observation.artifact.file.mediaType",
            "output.observation.artifact.sizeBytes",
            "output.observation.frame.frameId",
            "output.observation.frame.imageWidth",
            "output.observation.frame.imageHeight");

    private HistoryBrowserFields() {}

    static Map<String, String> read(Reader source) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (JsonParser parser = FACTORY.createParser(source)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Map.of();
            }
            object(parser, "", result);
            if (parser.nextToken() != null) {
                return Map.of();
            }
        }
        return Map.copyOf(result);
    }

    private static boolean scalar(String path, JsonToken token) {
        if (path.equals("success")) {
            return token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE;
        }
        if (path.equals("output.version")
                || path.endsWith(".sizeBytes")
                || path.endsWith(".imageWidth")
                || path.endsWith(".imageHeight")) {
            return token == JsonToken.VALUE_NUMBER_INT;
        }
        return token == JsonToken.VALUE_STRING;
    }

    private static void object(JsonParser parser, String prefix, Map<String, String> result) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String path = prefix + parser.currentName();
            JsonToken token = parser.nextToken();
            if (FIELDS.contains(path) && scalar(path, token)) {
                result.put(path, parser.getText());
            } else if (token == JsonToken.START_OBJECT
                    && FIELDS.stream().anyMatch(field -> field.startsWith(path + '.'))) {
                object(parser, path + '.', result);
            } else {
                parser.skipChildren();
            }
        }
    }
}
