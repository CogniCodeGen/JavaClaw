package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderReasoningSummary;

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

    /** 保持 Provider 高级选项的 v2 wire 形状，不把 Jackson 注解带入 API 模块。 */
    static final class ProviderAdapterOptionsSerializer extends StdSerializer<ProviderAdapterOptions> {
        ProviderAdapterOptionsSerializer() {
            super(ProviderAdapterOptions.class);
        }

        @Override
        public void serialize(ProviderAdapterOptions value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeStartObject(value);
            generator.writeStringField("adapter", value.adapter().name());
            switch (value) {
                case ProviderAdapterOptions.OpenAiCompatible options -> {
                    writeOptional(generator, "organization", options.organization());
                    writeOptional(generator, "project", options.project());
                    generator.writeNullField("apiVersion");
                    generator.writeStringField("reasoningSummary", ProviderReasoningSummary.AUTO.name());
                }
                case ProviderAdapterOptions.Anthropic ignored -> {
                    generator.writeNullField("organization");
                    generator.writeNullField("project");
                    generator.writeNullField("apiVersion");
                    generator.writeStringField("reasoningSummary", ProviderReasoningSummary.AUTO.name());
                }
                case ProviderAdapterOptions.GoogleGenAi options -> {
                    generator.writeNullField("organization");
                    generator.writeNullField("project");
                    writeOptional(generator, "apiVersion", options.apiVersion());
                    generator.writeStringField("reasoningSummary", ProviderReasoningSummary.AUTO.name());
                }
                case ProviderAdapterOptions.OpenAiResponses options -> {
                    writeOptional(generator, "organization", options.organization());
                    writeOptional(generator, "project", options.project());
                    generator.writeNullField("apiVersion");
                    generator.writeStringField(
                            "reasoningSummary", options.reasoningSummary().name());
                }
            }
            generator.writeEndObject();
        }

        private static void writeOptional(JsonGenerator generator, String field, Optional<String> value)
                throws IOException {
            if (value.isPresent()) {
                generator.writeStringField(field, value.orElseThrow());
            } else {
                generator.writeNullField(field);
            }
        }
    }

    /** 根据稳定 {@code adapter} 判别字段恢复唯一的 Provider 高级选项类型。 */
    static final class ProviderAdapterOptionsDeserializer extends StdDeserializer<ProviderAdapterOptions> {
        private static final Set<String> FIELDS =
                Set.of("adapter", "organization", "project", "apiVersion", "reasoningSummary");

        ProviderAdapterOptionsDeserializer() {
            super(ProviderAdapterOptions.class);
        }

        @Override
        public ProviderAdapterOptions deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.readValueAsTree();
            if (!(node instanceof ObjectNode object) || !fieldNames(object).equals(FIELDS)) {
                return context.reportInputMismatch(
                        ProviderAdapterOptions.class, "Provider adapter options must contain the exact v2 fields");
            }
            ProviderAdapter adapter = enumValue(object, "adapter", ProviderAdapter.class, context);
            Optional<String> organization = optionalText(object, "organization", context);
            Optional<String> project = optionalText(object, "project", context);
            Optional<String> apiVersion = optionalText(object, "apiVersion", context);
            ProviderReasoningSummary summary =
                    enumValue(object, "reasoningSummary", ProviderReasoningSummary.class, context);
            try {
                return create(adapter, organization, project, apiVersion, summary, context);
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(
                        ProviderAdapterOptions.class, "Provider adapter options do not satisfy their typed contract");
            }
        }

        private static ProviderAdapterOptions create(
                ProviderAdapter adapter,
                Optional<String> organization,
                Optional<String> project,
                Optional<String> apiVersion,
                ProviderReasoningSummary summary,
                DeserializationContext context)
                throws IOException {
            return switch (adapter) {
                case OPENAI_COMPATIBLE -> {
                    requireEmpty(apiVersion, "apiVersion", context);
                    requireSummary(summary, ProviderReasoningSummary.AUTO, context);
                    yield new ProviderAdapterOptions.OpenAiCompatible(organization, project);
                }
                case ANTHROPIC -> {
                    requireEmpty(organization, "organization", context);
                    requireEmpty(project, "project", context);
                    requireEmpty(apiVersion, "apiVersion", context);
                    requireSummary(summary, ProviderReasoningSummary.AUTO, context);
                    yield new ProviderAdapterOptions.Anthropic();
                }
                case GOOGLE_GENAI -> {
                    requireEmpty(organization, "organization", context);
                    requireEmpty(project, "project", context);
                    requireSummary(summary, ProviderReasoningSummary.AUTO, context);
                    yield new ProviderAdapterOptions.GoogleGenAi(apiVersion);
                }
                case OPENAI_RESPONSES -> {
                    requireEmpty(apiVersion, "apiVersion", context);
                    yield new ProviderAdapterOptions.OpenAiResponses(organization, project, summary);
                }
            };
        }

        private static Set<String> fieldNames(ObjectNode object) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            object.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }

        private static Optional<String> optionalText(ObjectNode object, String field, DeserializationContext context)
                throws IOException {
            JsonNode value = object.get(field);
            if (value.isNull()) {
                return Optional.empty();
            }
            if (!value.isTextual()) {
                return context.reportInputMismatch(ProviderAdapterOptions.class, "%s must be a string or null", field);
            }
            return Optional.of(value.textValue());
        }

        private static <E extends Enum<E>> E enumValue(
                ObjectNode object, String field, Class<E> type, DeserializationContext context) throws IOException {
            JsonNode value = object.get(field);
            if (!value.isTextual()) {
                return context.reportInputMismatch(ProviderAdapterOptions.class, "%s must be an enum string", field);
            }
            try {
                return Enum.valueOf(type, value.textValue());
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(
                        ProviderAdapterOptions.class, "%s contains an unknown enum value", field);
            }
        }

        private static void requireEmpty(Optional<String> value, String field, DeserializationContext context)
                throws IOException {
            if (value.isPresent()) {
                context.reportInputMismatch(
                        ProviderAdapterOptions.class, "%s is not supported by this Provider adapter", field);
            }
        }

        private static void requireSummary(
                ProviderReasoningSummary actual, ProviderReasoningSummary expected, DeserializationContext context)
                throws IOException {
            if (actual != expected) {
                context.reportInputMismatch(
                        ProviderAdapterOptions.class, "reasoningSummary is not supported by this Provider adapter");
            }
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
