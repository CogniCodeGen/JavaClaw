package com.example.hello;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.protocol.v2.Contribution;
import com.javaclaw.plugin.protocol.v2.HookRequest;
import com.javaclaw.plugin.protocol.v2.HookResponse;
import com.javaclaw.plugin.sdk.Cancellation;
import com.javaclaw.plugin.sdk.Plugin;
import com.javaclaw.plugin.sdk.PluginCall;
import com.javaclaw.plugin.sdk.PluginContext;
import com.javaclaw.plugin.sdk.PluginEvents;
import java.util.List;
import java.util.Locale;

/** 独立进程示例，演示声明式工具和不可变配置。 */
public final class HelloPlugin implements Plugin {
    private String greeting = "你好";
    private String workspace = "";
    @Override public List<Contribution> contributions() {
        return List.of(Contribution.newBuilder().setId("hello_shout").setKind(CapabilityKind.TOOL)
                .setName("大声问好").setDescription("把输入文本转为大写并加上问候语")
                .setInputSchemaJson("{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}")
                .setOutputSchemaJson("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}").build(),
                contribution("hello_style", CapabilityKind.SKILL, "问候回复规范", "{}", "{\"content\":\"用简洁友好的中文回复问候。\"}"),
                contribution("hello_context", CapabilityKind.CONTEXT, "工作区问候上下文", "{}", "{}"),
                contribution("hello_workflow", CapabilityKind.WORKFLOW, "生成问候语", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}", "{}"),
                contribution("hello_policy", CapabilityKind.HOOK, "示例预算约束", "{}",
                        "{\"phase\":\"constraint\",\"constraint\":true,\"priority\":100}"));
    }
    private static Contribution contribution(String id, CapabilityKind kind, String name, String schema, String metadata) {
        return Contribution.newBuilder().setId(id).setKind(kind).setName(name).setDescription(name)
                .setInputSchemaJson(schema).setOutputSchemaJson("{}").setMetadataJson(metadata).build();
    }
    @Override public void start(PluginContext context) {
        JsonObject configuration = JsonParser.parseString(context.configurationJson()).getAsJsonObject();
        workspace = context.identity().getWorkspaceId();
        if (configuration.has("greeting")) greeting = configuration.get("greeting").getAsString();
    }
    @Override public void execute(PluginCall call, PluginEvents events) {
        call.cancellation().throwIfCancelled();
        JsonObject payload = JsonParser.parseString(call.payloadJson()).getAsJsonObject();
        if (call.kind() == CapabilityKind.WORKFLOW) {
            JsonObject configuration = payload.has("configuration") ? payload.getAsJsonObject("configuration") : new JsonObject();
            String text = configuration.has("text") ? configuration.get("text").getAsString() : "世界";
            JsonObject values = new JsonObject(); values.addProperty("hello.result", greeting + "，" + text);
            JsonObject result = new JsonObject(); result.add("setValues", values); result.addProperty("output", greeting + "，" + text);
            events.data(result.toString());
            return;
        }
        String input = payload.get("text").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("text", greeting + "，" + input.toUpperCase(Locale.ROOT) + "!!!");
        events.data(result.toString());
    }
    @Override public String retrieve(PluginCall call) {
        call.cancellation().throwIfCancelled();
        if (call.kind() == CapabilityKind.SKILL) return "{\"text\":\"用简洁友好的中文回复问候。\"}";
        JsonObject result = new JsonObject(); result.addProperty("text", "当前工作区：" + workspace);
        return result.toString();
    }
    @Override public HookResponse hook(HookRequest request, Cancellation cancellation) {
        cancellation.throwIfCancelled();
        JsonObject output = new JsonObject(); output.addProperty("allowed", true);
        JsonObject input = JsonParser.parseString(request.getInputJson()).getAsJsonObject();
        if (input.has("budget") && input.get("budget").isJsonObject()) {
            JsonObject budget = input.getAsJsonObject("budget").deepCopy();
            if (budget.has("maxToolCalls")) budget.addProperty("maxToolCalls", Math.min(8, budget.get("maxToolCalls").getAsInt()));
            output.add("budget", budget);
        }
        return HookResponse.newBuilder().setOutputJson(output.toString())
                .addAllAllowedPermissions(request.getAllowedPermissionsList().stream()
                        .filter(value -> !value.endsWith(".delete")).toList())
                .setRemainderBudget(Math.min(8, request.getRemainderBudget())).build();
    }
}
