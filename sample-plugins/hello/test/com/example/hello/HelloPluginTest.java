package com.example.hello;

import com.google.gson.JsonParser;
import com.javaclaw.plugin.protocol.v2.CallRequest;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.protocol.v2.HookRequest;
import com.javaclaw.plugin.protocol.v2.PluginIdentity;
import com.javaclaw.plugin.sdk.PluginCall;
import com.javaclaw.plugin.sdk.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HelloPluginTest {
    @TempDir Path data;

    @Test
    void contributionIdsAndKindsAreUniqueAndComplete() {
        var contributions = new HelloPlugin().contributions();
        assertEquals(5, contributions.size());
        assertEquals(5, contributions.stream().map(value -> value.getId()).distinct().count());
        assertEquals(java.util.Set.of(CapabilityKind.TOOL, CapabilityKind.SKILL,
                        CapabilityKind.CONTEXT, CapabilityKind.WORKFLOW, CapabilityKind.HOOK),
                contributions.stream().map(value -> value.getKind()).collect(java.util.stream.Collectors.toSet()));
        contributions.forEach(value -> {
            assertTrue(JsonParser.parseString(value.getInputSchemaJson()).isJsonObject());
            assertTrue(JsonParser.parseString(value.getOutputSchemaJson()).isJsonObject());
        });
    }

    @Test
    void configurationAndWorkspaceStayWithinEachPluginInstance() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = new HelloPlugin();
            first.start(new PluginContext(PluginIdentity.newBuilder().setWorkspaceId("workspace-a").build(),
                    "{\"greeting\":\"欢迎\"}", data, executor,
                    (context, service, operation, payload, events) -> { throw new AssertionError("不应调用宿主"); },
                    () -> false));
            var result = new AtomicReference<String>();
            first.execute(call(CapabilityKind.TOOL, "hello_shout", "{\"text\":\"中文i\"}"), result::set);
            assertEquals("欢迎，中文I!!!", JsonParser.parseString(result.get()).getAsJsonObject().get("text").getAsString());
            assertEquals("当前工作区：workspace-a", JsonParser.parseString(first.retrieve(
                    call(CapabilityKind.CONTEXT, "hello_context", "{}"))).getAsJsonObject().get("text").getAsString());
            var second = new HelloPlugin();
            second.execute(call(CapabilityKind.TOOL, "hello_shout", "{\"text\":\"world\"}"), result::set);
            assertEquals("你好，WORLD!!!", JsonParser.parseString(result.get()).getAsJsonObject().get("text").getAsString());
        }
    }

    @Test
    void workflowReturnsOnlyItsExplicitPatchAndOutput() {
        var result = new AtomicReference<String>();
        new HelloPlugin().execute(call(CapabilityKind.WORKFLOW, "hello_workflow",
                "{\"configuration\":{\"text\":\"访客\"}}"), result::set);
        var json = JsonParser.parseString(result.get()).getAsJsonObject();
        assertEquals("你好，访客", json.get("output").getAsString());
        assertEquals("你好，访客", json.getAsJsonObject("setValues").get("hello.result").getAsString());
        assertEquals(1, json.getAsJsonObject("setValues").size());
    }

    @Test
    void hookCanOnlyTightenPermissionsAndBothBudgetRepresentations() {
        var plugin = new HelloPlugin();
        for (int remaining : new int[]{0, 3, 20}) {
            var response = plugin.hook(HookRequest.newBuilder().setInputJson(
                            "{\"budget\":{\"maxToolCalls\":" + remaining + "}}")
                    .setRemainderBudget(remaining).addAllowedPermissions("file.read")
                    .addAllowedPermissions("file.delete").build(), () -> false);
            assertEquals(java.util.List.of("file.read"), response.getAllowedPermissionsList());
            assertEquals(Math.min(8, remaining), response.getRemainderBudget());
            assertEquals(Math.min(8, remaining), JsonParser.parseString(response.getOutputJson())
                    .getAsJsonObject().getAsJsonObject("budget").get("maxToolCalls").getAsInt());
        }
    }

    @Test
    void cancellationPreventsEveryEntryPointFromProducingData() {
        var plugin = new HelloPlugin();
        var request = CallRequest.newBuilder().setContributionId("hello_shout").setPayloadJson("{}").build();
        var call = new PluginCall(CapabilityKind.TOOL, request, () -> true);
        assertThrows(CancellationException.class, () -> plugin.execute(call, ignored -> fail("不应产生结果")));
        assertThrows(CancellationException.class, () -> plugin.retrieve(call));
        assertThrows(CancellationException.class, () -> plugin.hook(HookRequest.getDefaultInstance(), () -> true));
    }

    private static PluginCall call(CapabilityKind kind, String id, String payload) {
        return new PluginCall(kind, CallRequest.newBuilder().setContributionId(id).setPayloadJson(payload).build(),
                () -> false);
    }
}
