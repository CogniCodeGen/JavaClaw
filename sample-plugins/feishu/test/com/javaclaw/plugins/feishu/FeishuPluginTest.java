package com.javaclaw.plugins.feishu;

import com.google.gson.JsonParser;
import com.javaclaw.plugin.protocol.v2.CallRequest;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.sdk.PluginCall;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 本地契约测试不启动飞书连接，不使用真实账号或发送消息。 */
class FeishuPluginTest {
    @Test
    void configurationRequiresBothCredentialsWithoutExposingTheirValues() {
        var plugin = new FeishuPlugin();
        for (String configuration : java.util.List.of("{}", "{\"appId\":\"dummy-id\"}",
                "{\"appSecret\":\"dummy-secret\"}", "{\"appId\":null,\"appSecret\":\"dummy-secret\"}")) {
            var errors = plugin.validateConfiguration(configuration);
            assertFalse(errors.isEmpty());
            assertFalse(errors.toString().contains("dummy"));
        }
        assertTrue(plugin.validateConfiguration("{\"appId\":\"dummy-id\",\"appSecret\":\"dummy-secret\"}").isEmpty());
    }

    @Test
    void contributionsDeclareOnlyTheTwoToolsAndReplySkill() {
        var contributions = new FeishuPlugin().contributions();
        assertEquals(java.util.List.of("feishu_send", "feishu_messages", "feishu_reply_style"),
                contributions.stream().map(value -> value.getId()).toList());
        assertEquals(java.util.List.of(CapabilityKind.TOOL, CapabilityKind.TOOL, CapabilityKind.SKILL),
                contributions.stream().map(value -> value.getKind()).toList());
        var required = JsonParser.parseString(contributions.getFirst().getInputSchemaJson())
                .getAsJsonObject().getAsJsonArray("required");
        assertEquals(2, required.size());
        assertEquals("receive_id", required.get(0).getAsString());
        assertEquals("text", required.get(1).getAsString());
    }

    @Test
    void historyIsEmptyAndScopedToTheNewPluginWithoutConnecting() throws Exception {
        var result = new AtomicReference<String>();
        new FeishuPlugin().execute(call(CapabilityKind.TOOL, "feishu_messages", false), result::set);
        assertEquals(0, JsonParser.parseString(result.get()).getAsJsonObject().getAsJsonArray("messages").size());
    }

    @Test
    void cancellationAndUnknownContributionsNeverReachTheTransport() {
        var plugin = new FeishuPlugin();
        assertThrows(CancellationException.class, () -> plugin.execute(
                call(CapabilityKind.TOOL, "feishu_send", true), ignored -> fail("不应产生数据")));
        assertThrows(CancellationException.class, () -> plugin.retrieve(call(CapabilityKind.SKILL, "feishu_reply_style", true)));
        assertThrows(IllegalArgumentException.class, () -> plugin.execute(
                call(CapabilityKind.TOOL, "unknown", false), ignored -> fail("不应产生数据")));
        assertThrows(IllegalArgumentException.class, () -> plugin.execute(
                call(CapabilityKind.TOOL, "feishu_send", false), ignored -> fail("不应产生数据")));
    }

    @Test
    void skillRetrievalAndRepeatedDrainDoNotRequireAnAccount() {
        var plugin = new FeishuPlugin();
        var text = JsonParser.parseString(plugin.retrieve(call(CapabilityKind.SKILL, "feishu_reply_style", false)))
                .getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("纯文本"));
        assertThrows(IllegalArgumentException.class, () -> plugin.retrieve(call(CapabilityKind.CONTEXT, "unknown", false)));
        assertDoesNotThrow(() -> { plugin.beginDrain(); plugin.beginDrain(); plugin.close(); plugin.close(); });
    }

    private static PluginCall call(CapabilityKind kind, String id, boolean cancelled) {
        return new PluginCall(kind, CallRequest.newBuilder().setContributionId(id).setPayloadJson("{}").build(),
                () -> cancelled);
    }
}
