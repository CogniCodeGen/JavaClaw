package com.javaclaw.client;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfilePreset;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.client.facade.AgentProfileClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderProfileRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentProfileClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void SDK以只读列表暴露版本化Profile预设() {
        AgentProfilePreset preset = new AgentProfilePreset(
                "worker",
                1,
                "Worker",
                "专注执行",
                "完成交办目标并返回验证。",
                "a".repeat(64),
                new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(10)));
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            assertEquals("profile/preset/list", request.method());
            return JsonRpcResponse.success(
                    request.id(),
                    JSON.encode(new ProviderProfileRpcContracts.AgentProfilePresetListResult(List.of(preset))));
        });
        AgentProfileClient client = new AgentProfileClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(List.of(preset), client.presets());
    }
}
