package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.protocol.v2.Contribution;
import com.javaclaw.plugin.sdk.Plugin;
import com.javaclaw.plugin.sdk.PluginCall;
import com.javaclaw.plugin.sdk.PluginContext;
import com.javaclaw.plugin.sdk.PluginEvents;
import com.javaclaw.plugins.deliverance.catalog.DeliveranceCatalogOperations;
import com.javaclaw.plugins.deliverance.internal.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/** 新协议唯一入口；引擎和服务实现均为插件私有对象。 */
public final class DeliverancePlugin implements Plugin {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, ServiceRequestHandler> handlers = new ConcurrentHashMap<>();
    private final JsonNode manifest = readManifest();
    private final DeliveranceServicePlugin service = new DeliveranceServicePlugin();
    private final DeliveranceCatalogOperations catalog = new DeliveranceCatalogOperations(json, manifest.path("inference"));
    private DeliverancePluginContext context;

    @Override public List<Contribution> contributions() {
        return List.of(Contribution.newBuilder().setId("inference").setKind(CapabilityKind.MODEL)
                .setName("Deliverance 本地推理").setDescription("本地模型对话、嵌入与兼容量化模型目录")
                .setInputSchemaJson("{\"type\":\"object\"}")
                .setMetadataJson(manifest.path("inference").toString()).build());
    }

    @Override public void start(PluginContext sdk) throws Exception {
        context = new DeliverancePluginContext(sdk, json, handlers);
        service.start(context);
    }

    @Override public void execute(PluginCall call, PluginEvents events) throws Exception {
        if (catalog.supports(call.request().getOperation())) {
            catalog.execute(call, events);
            return;
        }
        String operation = call.request().getOperation();
        if (operation.equals("probe") && json.readTree(call.payloadJson()).hasNonNull("startup")) {
            probe(call, events);
            return;
        }
        String serviceId = switch (operation) {
            case "list", "models.sync", "models.publish" -> "deliverance/models";
            case "load", "unload", "chat", "embedding" -> "deliverance/" + operation;
            case "probe" -> "deliverance/health";
            case "gateway.configure", "gateway.status" -> "deliverance/gateway";
            case "logging.configure", "logging.status" -> "deliverance/logging";
            default -> throw new IllegalArgumentException("模型操作未注册: " + operation);
        };
        String privateOperation = switch (operation) {
            case "models.sync" -> "sync";
            case "models.publish" -> "publish";
            case "probe" -> "health";
            case "gateway.configure", "logging.configure" -> "configure";
            case "gateway.status", "logging.status" -> "status";
            default -> operation;
        };
        if (operation.equals("gateway.configure")) {
            PluginCall disable = new PluginCall(call.kind(), call.request().toBuilder()
                    .setPayloadJson("{\"enabled\":false}").build(), call.cancellation());
            handlers.get("deliverance/gateway").handle(invocation(disable, ignored -> { },
                    "deliverance/gateway", "configure"));
            context.configureGateway(json.readTree(call.payloadJson()));
            JsonNode catalogValue = json.readTree(call.payloadJson()).path("catalog");
            if (catalogValue.isObject()) {
                PluginCall sync = new PluginCall(call.kind(), call.request().toBuilder()
                        .setPayloadJson(catalogValue.toString()).build(), call.cancellation());
                handlers.get("deliverance/models").handle(invocation(sync, ignored -> { }, "deliverance/models", "sync"));
            }
            call = new PluginCall(call.kind(), call.request().toBuilder().setPayloadJson(
                    json.createObjectNode().put("enabled", json.readTree(call.payloadJson())
                            .path("enabled").asBoolean(false)).toString()).build(), call.cancellation());
        }
        ServiceRequestHandler handler = handlers.get(serviceId);
        if (handler == null) throw new IllegalStateException("模型服务尚未启动");
        handler.handle(invocation(call, events, serviceId, privateOperation));
    }

    private void probe(PluginCall call, PluginEvents events) throws Exception {
        DeliveranceServiceProtocol.LoadRequest request = json.readValue(call.payloadJson(),
                DeliveranceServiceProtocol.LoadRequest.class);
        call.cancellation().throwIfCancelled();
        try (DeliveranceEngine engine = new DeliveranceEngine(request.startup(), json)) {
            call.cancellation().throwIfCancelled();
            events.data(json.writeValueAsString(Map.of("model", new DeliveranceServiceProtocol.ModelStatus(
                    request.profileId(), request.startup().modelName(), request.startup().modelKind(), false,
                    engine.actualContextLength(), engine.actualEmbeddingDimensions(),
                    engine.selectedBackend(), engine.capabilities()))));
        }
    }

    private ServiceInvocation invocation(PluginCall call, PluginEvents events, String id, String operation) {
        return new ServiceInvocation() {
            public String requestId() { return call.request().getContext().getRequestId(); }
            public String serviceId() { return id; }
            public String operation() { return operation; }
            public String contentType() { return "application/json"; }
            public byte[] payload() { return call.payloadJson().getBytes(StandardCharsets.UTF_8); }
            public Instant deadline() { return Instant.ofEpochMilli(call.request().getContext().getDeadlineEpochMillis()); }
            public Map<String, String> metadata() { return Map.of(); }
            public CancellationToken cancellation() { return call.cancellation()::isCancelled; }
            public ServiceResponseChannel responses() { return new ServiceResponseChannel() {
                public void event(String type, byte[] payload) { emit(payload); }
                public void complete(String type, byte[] payload) { emit(payload); }
                public void fail(String code, String message, String type, byte[] payload) {
                    if ("cancelled".equals(code)) throw new CancellationException("模型请求已取消");
                    throw new IllegalStateException(code + ": " + message);
                }
                public void cancelled(String type, byte[] payload) { throw new CancellationException("模型请求已取消"); }
                private void emit(byte[] payload) { events.data(new String(payload, StandardCharsets.UTF_8)); }
            }; }
        };
    }

    private JsonNode readManifest() {
        try (var stream = getClass().getResourceAsStream("/plugin.json")) {
            return json.readTree(java.util.Objects.requireNonNull(stream, "缺少插件清单"));
        } catch (Exception failure) { throw new IllegalStateException("无法读取插件清单", failure); }
    }

    @Override public void beginDrain() { if (context != null) context.beginDrain(); }

    @Override public void close() throws Exception {
        service.stop();
        if (context != null) context.close();
    }
}
