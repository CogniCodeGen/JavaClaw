package com.javaclaw.client.extension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

/** 单个 Transcript 的 Core 调用关联索引；调用 ID 只在其 Turn 内有效，歧义身份失败关闭。 */
public final class CodingToolResultIndex {
    private final CanonicalJson json;
    private final Map<Key, ItemEnvelope> calls = new HashMap<>();
    private final Set<Key> ambiguous = new HashSet<>();

    /**
     * 创建由 UI 或 CLI 单线程拥有的索引。
     *
     * @param json 共享严格 Codec
     */
    public CodingToolResultIndex(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 清空当前 Transcript；切换会话或重新加载完整快照前调用。 */
    public void clear() {
        calls.clear();
        ambiguous.clear();
    }

    /**
     * 纳入一条服务端 Item；重复轮询同一身份安全，冲突调用不参与 typed 输出。
     *
     * @param item 通过 SDK 读取的持久化 Item
     */
    public void accept(ItemEnvelope item) {
        if (!CoreSchemas.TOOL_CALL.equals(item.schemaId()) || !"core".equals(item.producerId())) {
            return;
        }
        var call = json.decode(item.payload(), CorePayloads.ToolCall.class);
        Key key = new Key(item.turnId(), call.callId());
        var previous = calls.putIfAbsent(key, item);
        if (previous != null
                && (!previous.id().equals(item.id()) || !previous.payload().equals(item.payload()))) {
            ambiguous.add(key);
        }
    }

    /**
     * 查找结果关联的唯一调用，跨分页保留关联但不允许跨 Turn 或逆序匹配。
     *
     * @param item 当前 Core ToolResult
     * @return 已持久化调用；未知、歧义或非结果 Item 返回空
     */
    public Optional<ItemEnvelope> callFor(ItemEnvelope item) {
        if (!CoreSchemas.TOOL_RESULT.equals(item.schemaId()) || !"core".equals(item.producerId())) {
            return Optional.empty();
        }
        var result = json.decode(item.payload(), CorePayloads.ToolResult.class);
        Key key = new Key(item.turnId(), result.callId());
        if (ambiguous.contains(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(calls.get(key)).filter(call -> call.sequence() < item.sequence());
    }

    private record Key(TurnId turn, String callId) {}
}
