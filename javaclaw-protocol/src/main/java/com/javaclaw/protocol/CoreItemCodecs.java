package com.javaclaw.protocol;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemPayloadCodec;
import com.javaclaw.api.ItemSchemaRegistry;

/** Core Item 的共享 JSON codec catalog。 */
public final class CoreItemCodecs {
    private CoreItemCodecs() {}

    /**
     * 创建并注册全部 Core payload codec。
     *
     * @param json 共享规范 JSON 实例
     * @return 已冻结到当前注册快照的 registry
     */
    public static ItemSchemaRegistry createRegistry(CanonicalJson json) {
        ItemSchemaRegistry registry = new ItemSchemaRegistry();
        register(registry, json, CoreSchemas.MESSAGE, CorePayloads.Message.class);
        register(registry, json, CoreSchemas.TOOL_CALL, CorePayloads.ToolCall.class);
        register(registry, json, CoreSchemas.TOOL_RESULT, CorePayloads.ToolResult.class);
        register(registry, json, CoreSchemas.COMMAND, CorePayloads.Command.class);
        register(registry, json, CoreSchemas.FILE_CHANGE, CorePayloads.FileChange.class);
        register(registry, json, CoreSchemas.APPROVAL, CorePayloads.Approval.class);
        register(registry, json, CoreSchemas.INPUT, CorePayloads.Input.class);
        register(registry, json, CoreSchemas.SUBAGENT, CorePayloads.Subagent.class);
        register(registry, json, CoreSchemas.COMPACTION, CorePayloads.Compaction.class);
        register(registry, json, CoreSchemas.EFFECT_RECEIPT, EffectReceipt.class);
        register(registry, json, CoreSchemas.ERROR, CorePayloads.Error.class);
        return registry;
    }

    private static <T extends ItemPayload> void register(
            ItemSchemaRegistry registry, CanonicalJson json, String schemaId, Class<T> type) {
        registry.register(new JacksonItemPayloadCodec<>(schemaId, type, json));
    }

    private record JacksonItemPayloadCodec<T extends ItemPayload>(
            String schemaId, Class<T> payloadType, CanonicalJson json) implements ItemPayloadCodec<T> {
        @Override
        public com.javaclaw.api.CanonicalPayload encode(T payload) {
            return json.encode(payload);
        }

        @Override
        public T decode(com.javaclaw.api.CanonicalPayload payload) {
            return json.decode(payload, payloadType);
        }
    }
}
