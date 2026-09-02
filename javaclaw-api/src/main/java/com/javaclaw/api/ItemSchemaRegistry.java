package com.javaclaw.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Item codec 的线程安全注册表。
 *
 * <p>注册与撤销使用 copy-on-write；正在执行的 Turn 可以持有旧 Map 快照，而实时权限撤销由独立授权层处理。
 */
public final class ItemSchemaRegistry {
    private final AtomicReference<Map<String, ItemPayloadCodec<?>>> codecs = new AtomicReference<>(Map.of());

    /** 创建空注册表。 */
    public ItemSchemaRegistry() {}

    /**
     * 原子注册 codec；重复 schema 或重复 Java 类型会失败。
     *
     * @param codec 新 codec
     */
    public void register(ItemPayloadCodec<?> codec) {
        Objects.requireNonNull(codec, "codec");
        codecs.updateAndGet(current -> withCodec(current, codec));
    }

    /**
     * 原子撤销 schema，已保存 Item 仍能作为 Unknown 读取。
     *
     * @param schemaId schema 标识
     */
    public void unregister(String schemaId) {
        String normalized = Preconditions.text(schemaId, "schemaId");
        codecs.updateAndGet(current -> withoutCodec(current, normalized));
    }

    /**
     * 解码 payload；没有 codec 时返回 Unknown。
     *
     * @param schemaId schema 标识
     * @param payload 规范化 JSON
     * @return 已知或未知内容
     */
    public DecodedItemPayload decode(String schemaId, CanonicalPayload payload) {
        String normalized = Preconditions.text(schemaId, "schemaId");
        ItemPayloadCodec<?> codec = codecs.get().get(normalized);
        if (codec == null) {
            return new DecodedItemPayload.Unknown(normalized, payload);
        }
        return new DecodedItemPayload.Known(normalized, codec.decode(payload));
    }

    /**
     * 按 Java 类型查找 codec 并编码。
     *
     * @param payload 强类型内容
     * @return schema 与规范内容
     */
    public EncodedItemPayload encode(ItemPayload payload) {
        Objects.requireNonNull(payload, "payload");
        ItemPayloadCodec<?> codec = codecs.get().values().stream()
                .filter(candidate -> candidate.payloadType().equals(payload.getClass()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unregistered payload type: " + payload.getClass()));
        return encodeWith(codec, payload);
    }

    /**
     * 返回当前 codec 的不可变快照。
     *
     * @return schema 到 codec 的映射
     */
    public Map<String, ItemPayloadCodec<?>> snapshot() {
        return codecs.get();
    }

    private static Map<String, ItemPayloadCodec<?>> withCodec(
            Map<String, ItemPayloadCodec<?>> current, ItemPayloadCodec<?> codec) {
        String schemaId = Preconditions.text(codec.schemaId(), "codec.schemaId");
        if (current.containsKey(schemaId)) {
            throw new IllegalArgumentException("duplicate schema: " + schemaId);
        }
        Optional<ItemPayloadCodec<?>> duplicateType = current.values().stream()
                .filter(value -> value.payloadType().equals(codec.payloadType()))
                .findFirst();
        if (duplicateType.isPresent()) {
            throw new IllegalArgumentException("duplicate payload type: " + codec.payloadType());
        }
        LinkedHashMap<String, ItemPayloadCodec<?>> updated = new LinkedHashMap<>(current);
        updated.put(schemaId, codec);
        return Map.copyOf(updated);
    }

    private static Map<String, ItemPayloadCodec<?>> withoutCodec(
            Map<String, ItemPayloadCodec<?>> current, String schemaId) {
        if (!current.containsKey(schemaId)) {
            return current;
        }
        LinkedHashMap<String, ItemPayloadCodec<?>> updated = new LinkedHashMap<>(current);
        updated.remove(schemaId);
        return Map.copyOf(updated);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ItemPayload> EncodedItemPayload encodeWith(
            ItemPayloadCodec<?> rawCodec, ItemPayload payload) {
        ItemPayloadCodec<T> codec = (ItemPayloadCodec<T>) rawCodec;
        return new EncodedItemPayload(codec.schemaId(), codec.encode((T) payload));
    }
}
