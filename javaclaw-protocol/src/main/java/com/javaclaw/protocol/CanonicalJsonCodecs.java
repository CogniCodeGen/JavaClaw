package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import com.javaclaw.api.CanonicalPayload;

/** CanonicalJson 的值类型 codec；只负责 Jackson 边界，不拥有 ObjectMapper。 */
final class CanonicalJsonCodecs {
    private CanonicalJsonCodecs() {}

    static final class PathSerializer extends JsonSerializer<Path> {
        @Override
        public void serialize(Path value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(value.toString());
        }
    }

    static final class PathDeserializer extends StdDeserializer<Path> {
        PathDeserializer() {
            super(Path.class);
        }

        @Override
        public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (Path) context.handleUnexpectedToken(Path.class, parser);
            }
            return Path.of(parser.getValueAsString());
        }
    }

    static final class CanonicalPayloadSerializer extends JsonSerializer<CanonicalPayload> {
        @Override
        public void serialize(CanonicalPayload value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            ObjectMapper objectMapper = (ObjectMapper) generator.getCodec();
            generator.writeTree(objectMapper.readTree(value.json()));
        }
    }

    static final class CanonicalPayloadDeserializer extends StdDeserializer<CanonicalPayload> {
        CanonicalPayloadDeserializer() {
            super(CanonicalPayload.class);
        }

        @Override
        public CanonicalPayload deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            if (!(node instanceof ObjectNode object)) {
                throw context.weirdStringException(node.toString(), CanonicalPayload.class, "expected JSON object");
            }
            return new CanonicalPayload(CanonicalJson.sort(object).toString());
        }
    }

    /** Set 没有业务顺序；按元素的规范 JSON 排序，确保不同 JVM 进程生成相同 payload 和摘要。 */
    static final class DeterministicSetSerializer extends StdSerializer<Set<?>> {
        DeterministicSetSerializer() {
            super(Set.class, false);
        }

        @Override
        public void serialize(Set<?> value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            ObjectMapper objectMapper = (ObjectMapper) generator.getCodec();
            List<JsonNode> elements = new ArrayList<>(value.size());
            for (Object element : value) {
                elements.add(CanonicalJson.sort(objectMapper.valueToTree(element)));
            }
            elements.sort(Comparator.comparing(JsonNode::toString));
            generator.writeStartArray(value, elements.size());
            for (JsonNode element : elements) {
                generator.writeTree(element);
            }
            generator.writeEndArray();
        }
    }
}
