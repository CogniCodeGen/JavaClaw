package com.javaclaw.protocol;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.extension.spi.ExtensionId;

/** 将单值强类型标识统一映射为标量 wire 文本，不暴露 Java record 的 {@code value} 包装。 */
final class IdentifierJsonModule extends SimpleModule {
    IdentifierJsonModule() {
        super("javaclaw-identifiers", Version.unknownVersion());
        registerUuid(ItemId.class, ItemId::new);
        registerUuid(PromptOptimizationId.class, PromptOptimizationId::new);
        registerUuid(ThreadId.class, ThreadId::new);
        registerUuid(TurnId.class, TurnId::new);
        registerUuid(WorkspaceId.class, WorkspaceId::new);
        registerUuid(WorktreeId.class, WorktreeId::new);
        addSerializer(ExtensionId.class, new IdentifierSerializer<>(ExtensionId.class, ExtensionId::value));
        addDeserializer(ExtensionId.class, new IdentifierDeserializer<>(ExtensionId.class, ExtensionId::new));
    }

    private <T> void registerUuid(Class<T> type, Function<UUID, T> factory) {
        addSerializer(type, new IdentifierSerializer<>(type, Object::toString));
        addDeserializer(type, new IdentifierDeserializer<>(type, value -> factory.apply(UUID.fromString(value))));
    }

    private static final class IdentifierSerializer<T> extends StdSerializer<T> {
        private final Function<T, String> renderer;

        private IdentifierSerializer(Class<T> type, Function<T, String> renderer) {
            super(type);
            this.renderer = Objects.requireNonNull(renderer, "renderer");
        }

        @Override
        public void serialize(T value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(renderer.apply(Objects.requireNonNull(value, "value")));
        }
    }

    private static final class IdentifierDeserializer<T> extends StdDeserializer<T> {
        private final Class<T> type;
        private final Function<String, T> parser;

        private IdentifierDeserializer(Class<T> type, Function<String, T> parser) {
            super(type);
            this.type = Objects.requireNonNull(type, "type");
            this.parser = Objects.requireNonNull(parser, "parser");
        }

        @Override
        public T deserialize(JsonParser input, DeserializationContext context) throws IOException {
            if (!input.hasToken(JsonToken.VALUE_STRING)) {
                return type.cast(context.handleUnexpectedToken(type, input));
            }
            String value = input.getValueAsString();
            try {
                return parser.apply(value);
            } catch (IllegalArgumentException invalid) {
                throw context.weirdStringException(value, type, "invalid identifier");
            }
        }
    }
}
