package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

/** 对稳定 Item 身份进行精确查询；访问权限必须由调用用例验证，不能直接暴露为 RPC。 */
public final class CoreItemReader {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final ItemRepository items;

    /**
     * 创建精确证据查询端口。
     *
     * @param database App Server 独占数据库
     * @param json Core codec
     */
    public CoreItemReader(H2Database database, CanonicalJson json) {
        this.transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.items = new ItemRepository(new TurnRepository());
    }

    /**
     * 按稳定 ID 读取来源，不扫描整个 Thread。
     *
     * @param id 精确 Item ID
     * @return 存在时的不可变来源
     */
    public Optional<ItemEnvelope> findItem(ItemId id) {
        return execute(connection -> items.find(connection, Objects.requireNonNull(id, "id")));
    }

    /**
     * 在来源 Turn 中定位结果对应的工具调用；返回前检查 Core producer。
     *
     * @param turnId 来源 Turn
     * @param callId 持久调用标识
     * @return 匹配调用，不跨 Thread 推测名称
     */
    public Optional<CorePayloads.ToolCall> findToolCall(TurnId turnId, String callId) {
        return execute(connection -> {
            // 只游标读取来源 Turn 的 Core 调用；避免为一次预览同时物化全部工具参数。
            try (var query = connection.prepareStatement(
                    "SELECT PAYLOAD FROM CORE.ITEM WHERE TURN_ID = ? AND SCHEMA_ID = ? AND PRODUCER_ID = 'core' ORDER BY SEQUENCE")) {
                query.setString(1, turnId.toString());
                query.setString(2, CoreSchemas.TOOL_CALL);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        var call = json.decode(new CanonicalPayload(rows.getString(1)), CorePayloads.ToolCall.class);
                        if (call.callId().equals(callId)) {
                            return Optional.of(call);
                        }
                    }
                }
            }
            return Optional.empty();
        });
    }

    /**
     * 按已授权结果核验先行、完整提交且唯一的 Core 工具调用；重复或后到身份不能用于签发浏览器预览。
     *
     * @param result 调用方已经验证访问权的持久结果 Item
     * @param callId 结果中的完整调用标识
     * @return 唯一已完成的先行调用；缺失、未完成、重复或后到时为空，不改变旧 findToolCall 的语义
     */
    public Optional<CorePayloads.ToolCall> findPriorToolCall(ItemEnvelope result, String callId) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(callId, "callId");
        return execute(connection -> {
            CorePayloads.ToolCall found = null;
            try (var query = connection.prepareStatement(
                    "SELECT SEQUENCE,PAYLOAD,STATUS FROM CORE.ITEM WHERE TURN_ID=? AND SCHEMA_ID=? AND PRODUCER_ID='core' ORDER BY SEQUENCE")) {
                query.setString(1, result.turnId().toString());
                query.setString(2, CoreSchemas.TOOL_CALL);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        var call = json.decode(new CanonicalPayload(rows.getString(2)), CorePayloads.ToolCall.class);
                        if (call.callId().equals(callId)) {
                            if (found != null
                                    || rows.getLong(1) >= result.sequence()
                                    || !ItemStatus.COMPLETED.name().equals(rows.getString(3))) {
                                return Optional.empty();
                            }
                            found = call;
                        }
                    }
                }
            }
            return Optional.ofNullable(found);
        });
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (Exception failure) {
            throw new PersistenceException("无法读取 Core Item 证据", failure);
        }
    }
}
