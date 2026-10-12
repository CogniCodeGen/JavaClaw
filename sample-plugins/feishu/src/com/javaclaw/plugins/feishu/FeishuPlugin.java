package com.javaclaw.plugins.feishu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javaclaw.plugin.protocol.v2.CallContext;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.protocol.v2.Contribution;
import com.javaclaw.plugin.sdk.*;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 飞书连接和第三方 SDK 完全位于插件进程，通过受限宿主 RPC 发起对话。 */
public final class FeishuPlugin implements Plugin {
    private final ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    private final Set<String> seen = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 512; }
            }));
    private PluginContext context;
    private com.lark.oapi.Client api;
    private com.lark.oapi.ws.Client socket;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.Semaphore conversations = new java.util.concurrent.Semaphore(16);
    @Override public List<Contribution> contributions() {
        return List.of(tool("feishu_send", "发送飞书消息", "主动向飞书用户或会话发送文本"),
                tool("feishu_messages", "飞书消息历史", "查看最近收到的飞书文本消息"),
                Contribution.newBuilder().setId("feishu_reply_style").setKind(CapabilityKind.SKILL)
                        .setName("飞书回复规范").setDescription("飞书对话的简洁纯文本风格")
                        .setInputSchemaJson("{}").setOutputSchemaJson("{}")
                        .setMetadataJson("{\"content\":\"在飞书中简洁回复，优先纯文本。长内容分段，避免复杂 Markdown 表格。\"}").build());
    }
    private static Contribution tool(String id, String name, String description) {
        return Contribution.newBuilder().setId(id).setKind(CapabilityKind.TOOL).setName(name)
                .setDescription(description).setInputSchemaJson(id.equals("feishu_send")
                        ? "{\"type\":\"object\",\"required\":[\"receive_id\",\"text\"],\"properties\":{\"receive_id\":{\"type\":\"string\"},\"id_type\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}"
                        : "{\"type\":\"object\"}")
                .setOutputSchemaJson("{\"type\":\"object\"}").build();
    }
    @Override public List<String> validateConfiguration(String configurationJson) {
        JsonObject values = JsonParser.parseString(configurationJson).getAsJsonObject();
        return text(values, "appId").isBlank() || text(values, "appSecret").isBlank()
                ? List.of("请配置 App ID 和 App Secret") : List.of();
    }
    @Override public void start(PluginContext context) {
        this.context = context;
        JsonObject values = JsonParser.parseString(context.configurationJson()).getAsJsonObject();
        String appId = text(values, "appId"), appSecret = text(values, "appSecret");
        api = com.lark.oapi.Client.newBuilder(appId, appSecret).build();
        var dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override public void handle(P2MessageReceiveV1 event) { onMessage(event); }
                }).build();
        socket = new com.lark.oapi.ws.Client.Builder(appId, appSecret).eventHandler(dispatcher).autoReconnect(true).build();
        context.executor().submit(() -> { socket.start(); return null; });
    }
    private void onMessage(P2MessageReceiveV1 event) {
        var message = event.getEvent().getMessage();
        if (draining.get() || message.getMessageId() == null || !seen.add(message.getMessageId())
                || !"text".equals(message.getMessageType()) || context.cancellation().isCancelled()) return;
        String prompt = text(JsonParser.parseString(message.getContent()).getAsJsonObject(), "text")
                .replaceAll("@_user_\\d+", "").replace("@_all", "").trim();
        if (prompt.isBlank() || prompt.length() > 65_536 || !conversations.tryAcquire()) return;
        history.addLast(prompt);
        while (history.size() > 100) history.pollFirst();
        try { context.executor().submit(() -> {
            try {
            JsonObject payload = new JsonObject();
            payload.addProperty("prompt", prompt);
            payload.addProperty("conversationId", message.getChatId());
            payload.addProperty("source", "feishu");
            CallContext call = CallContext.newBuilder().setIdentity(context.identity())
                    .setRequestId(UUID.randomUUID().toString()).setRunId("feishu:" + message.getChatId())
                    .setDeadlineEpochMillis(System.currentTimeMillis() + 120_000).build();
            String result = context.host().invoke(call, "agent", "chat", payload.toString(), ignored -> { });
            send(message.getChatId(), "chat_id", text(JsonParser.parseString(result).getAsJsonObject(), "text"));
            } finally { conversations.release(); }
            return null;
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { conversations.release(); }
    }
    @Override public void execute(PluginCall call, PluginEvents events) throws Exception {
        call.cancellation().throwIfCancelled();
        JsonObject args = JsonParser.parseString(call.payloadJson()).getAsJsonObject();
        JsonObject result = new JsonObject();
        if (call.contributionId().equals("feishu_send")) {
            send(text(args, "receive_id"), text(args, "id_type"), text(args, "text"));
            result.addProperty("status", "已发送");
        } else if (call.contributionId().equals("feishu_messages")) {
            result.add("messages", new com.google.gson.Gson().toJsonTree(List.copyOf(history)));
        } else throw new IllegalArgumentException("未知飞书工具");
        events.data(result.toString());
    }
    private void send(String receiveId, String idType, String text) throws Exception {
        if (receiveId.isBlank()) throw new IllegalArgumentException("缺少接收者");
        JsonObject content = new JsonObject(); content.addProperty("text", text);
        var response = api.im().v1().message().create(CreateMessageReq.newBuilder().receiveIdType(idType.isBlank() ? "chat_id" : idType)
                .createMessageReqBody(CreateMessageReqBody.newBuilder().receiveId(receiveId).msgType("text")
                        .content(content.toString()).uuid(UUID.randomUUID().toString()).build()).build());
        if (!response.success()) throw new IllegalStateException("飞书拒绝消息，错误码 " + response.getCode());
    }
    private static String text(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
    @Override public String retrieve(PluginCall call) {
        call.cancellation().throwIfCancelled();
        if (call.kind() != CapabilityKind.SKILL) throw new IllegalArgumentException("未知飞书上下文贡献");
        return "{\"text\":\"在飞书中简洁回复，优先纯文本。长内容分段，避免复杂 Markdown 表格。\"}";
    }
    @Override public void beginDrain() {
        if (!draining.compareAndSet(false, true)) return;
        stopSocket();
    }
    @Override public void close() { draining.set(true); stopSocket(); }
    private void stopSocket() {
        if (socket == null) return;
        for (String method : List.of("stop", "close", "disconnect", "shutdown")) {
            try { socket.getClass().getMethod(method).invoke(socket); return; }
            catch (ReflectiveOperationException ignored) { }
        }
        // SDK 未提供关闭方法时，由宿主终止整个插件进程回收连接与线程。
    }
}
