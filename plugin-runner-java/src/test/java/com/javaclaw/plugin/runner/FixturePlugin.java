package com.javaclaw.plugin.runner;

import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.*;
import java.util.List;

/** 测试工件仅由独立插件类加载器实例化。 */
public final class FixturePlugin implements Plugin {
    private PluginContext context;
    @Override public void start(PluginContext value) { context = value; }
    static { System.setProperty("javaclaw.fixture.loaded", "true"); }
    @Override public List<Contribution> contributions() {
        return List.of(
                contribution("echo", CapabilityKind.TOOL),
                contribution("skill", CapabilityKind.SKILL),
                contribution("workflow", CapabilityKind.WORKFLOW),
                contribution("model", CapabilityKind.MODEL),
                contribution("context", CapabilityKind.CONTEXT),
                contribution("policy", CapabilityKind.HOOK));
    }
    private Contribution contribution(String id, CapabilityKind kind) {
        return Contribution.newBuilder().setId(id).setKind(kind).setName(id)
                .setInputSchemaJson("{}").setOutputSchemaJson("{}").build();
    }
    @Override public void execute(PluginCall call, PluginEvents events) throws Exception {
        if (call.request().getOperation().equals("background")) {
            var child = context.executor().submit(() -> {
                while (!java.nio.file.Files.exists(context.privateDataDirectory().resolve("release"))) Thread.sleep(10);
                return null;
            });
            events.data(call.payloadJson());
            child.get();
            return;
        }
        if (call.kind() == CapabilityKind.WORKFLOW) {
            events.data("{\"setValues\":{\"result.text\":\"完成\"},\"output\":\"完成\"}"); return;
        }
        if (call.request().getOperation().equals("private-dependency")) {
            Class<?> dependency = getClass().getClassLoader().loadClass("com.google.gson.Gson");
            String value = (String) dependency.getMethod("isolated").invoke(dependency.getConstructor().newInstance());
            events.data("{\"dependency\":\"" + value + "\"}");
            return;
        }
        events.data(call.payloadJson());
        if (call.request().getOperation().equals("wait")) {
            while (true) { call.cancellation().throwIfCancelled(); Thread.sleep(10); }
        }
    }
    @Override public String retrieve(PluginCall call) {
        return call.kind() == CapabilityKind.SKILL ? "{\"text\":\"中文回复规范\"}" : "{\"context\":true}";
    }
    @Override public void beginDrain() throws Exception {
        java.nio.file.Files.writeString(context.privateDataDirectory().resolve("drain-count"), "drain\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    @Override public HookResponse hook(HookRequest request, Cancellation cancellation) {
        boolean escalate = request.getInputJson().contains("escalate");
        return HookResponse.newBuilder().setOutputJson("{}")
                .addAllowedPermissions(request.getInputJson().contains("wildcard") ? "*"
                        : request.getInputJson().contains("extra") || escalate ? "admin" : "read")
                .setRemainderBudget(escalate ? 1000 : 1).build();
    }
}
