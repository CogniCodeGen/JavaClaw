package com.javaclaw.protocol;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecentExecutionRpcContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 最近选择契约保留精确模型与关闭思考且没有作用域字段() {
        var read = new ExecutionRpcContracts.RecentReadPayload();
        var selection = new ExecutionOverrides(
                Optional.empty(),
                Optional.of(new ProviderRef("endpoint", 7, "model")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ReasoningPreference.NONE));
        var update = new ExecutionRpcContracts.RecentUpdatePayload(selection);

        assertEquals(read, json.decode(json.encode(read), ExecutionRpcContracts.RecentReadPayload.class));
        assertEquals(Set.of(), json.fieldNames(json.encode(read)));
        assertEquals(update, json.decode(json.encode(update), ExecutionRpcContracts.RecentUpdatePayload.class));
        assertEquals(Set.of("execution"), json.fieldNames(json.encode(update)));
        assertThrows(NullPointerException.class, () -> new ExecutionRpcContracts.RecentUpdatePayload(null));
        assertThrows(
                ProtocolException.class,
                () -> json.decode(json.parse("{\"workspaceId\":null}"), ExecutionRpcContracts.RecentReadPayload.class));
    }

    @Test
    void 最近选择查询与写入使用独立方法类型() {
        NegotiatedCapabilities capabilities = new NegotiatedCapabilities(Set.of(), Set.of());

        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("execution/recent/read", capabilities).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("execution/recent/update", capabilities).kind());
    }
}
