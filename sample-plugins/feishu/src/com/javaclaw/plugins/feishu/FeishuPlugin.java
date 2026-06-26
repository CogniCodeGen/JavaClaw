package com.javaclaw.plugins.feishu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javaclaw.plugin.api.JavaClawPlugin;
import com.javaclaw.plugin.api.PluginContext;
import com.javaclaw.plugin.api.PluginSkill;
import com.javaclaw.plugin.api.PluginTool;
import com.javaclaw.plugin.api.SkillProvider;
import com.javaclaw.plugin.api.ToolProvider;
import com.javaclaw.plugin.api.exec.ServiceHandle;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 飞书消息助手插件 —— 经飞书自建应用的<b>长连接(WebSocket)</b>接收用户消息，
 * 调用宿主 CHAT 能力生成回复，再经飞书 OpenAPI 发回。
 *
 * <p>展示插件框架三大能力的组合：<b>配置注入</b>(appId/appSecret)、<b>后台监听</b>
 * (ctx.exec().background 跑长连接，虚拟线程承载)、<b>CHAT 能力</b>(ctx.chat().ask)。</p>
 *
 * <p>三方依赖(飞书 oapi-sdk 及其传递依赖)放在 {@code plugins/feishu.lib/}，由插件类加载器
 * 子优先加载，与宿主及其他插件隔离。</p>
 *
 * <p><b>飞书侧前置</b>：开放平台建自建应用 → 开通"机器人" → 事件订阅选"长连接"模式 →
 * 添加事件 {@code im.message.receive_v1} → 申请权限 {@code im:message}/{@code im:message:send_as_bot} → 发布。</p>
 *
 * @author JavaClaw
 */
public class FeishuPlugin implements JavaClawPlugin, SkillProvider, ToolProvider {

    /** 收到的一条飞书消息（供 feishu_messages 工具查看） */
    private record ReceivedMessage(String time, String chatId, String chatType, String sender, String text) {
    }

    /** 最近收到的消息（有界，feishu_messages 查看用） */
    private final Deque<ReceivedMessage> history = new ConcurrentLinkedDeque<>();
    private static final int HISTORY_CAP = 100;

    /** 消息去重(飞书 at-least-once 投递)：按 message_id 的有界 LRU 集合 */
    private final Set<String> seen = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 512;
                }
            }));

    private PluginContext ctx;
    private com.lark.oapi.Client apiClient;        // OpenAPI 客户端(发消息，自动管 token)
    private com.lark.oapi.ws.Client wsClient;      // 长连接客户端(收消息)
    private ServiceHandle wsHandle;

    @Override
    public void start(PluginContext ctx) throws Exception {
        this.ctx = ctx;

        // 飞书技能经 SkillProvider 动态注册（见 skills()），由宿主并入渐进式暴露、卸载即移除，无须在此处理。
        String appId = ctx.config().get("appId", "").trim();
        String appSecret = ctx.config().get("appSecret", "").trim();
        if (appId.isEmpty() || appSecret.isEmpty()) {
            log("未配置 App ID / App Secret，插件空转。请在插件中心填写后重新启用。");
            return;
        }

        // OpenAPI 客户端：用于回复消息(SDK 自动管理 tenant_access_token)
        this.apiClient = com.lark.oapi.Client.newBuilder(appId, appSecret).build();

        // 事件分发器：注册"接收消息"处理器(长连接模式无须 verificationToken/encryptKey)
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        try {
                            onMessage(event);
                        } catch (Exception e) {
                            log("处理消息异常：" + e.getMessage());
                        }
                    }
                })
                .build();

        this.wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();

        // 长连接跑在宿主托管的虚拟线程上(阻塞 I/O 不占用平台载体线程)
        this.wsHandle = ctx.exec().background("feishu-ws", c -> {
            log("飞书长连接启动中...");
            wsClient.start();
            log("飞书长连接已结束");
        });
        log("已启用，等待飞书消息（appId=" + appId + "）");
    }

    /** 收到一条消息：去重 → 取文本 → 异步调 AI → 回复。 */
    private void onMessage(P2MessageReceiveV1 event) {
        EventMessage msg = event.getEvent().getMessage();
        String msgId = msg.getMessageId();
        if (msgId == null || !seen.add(msgId)) {
            return;   // 重复投递，忽略
        }
        final String chatId = msg.getChatId();
        if (!"text".equals(msg.getMessageType())) {
            replyText(chatId, "暂仅支持文本消息");
            return;
        }
        String text = stripMentions(parseText(msg.getContent()));
        if (text.isBlank()) {
            return;
        }
        // 记入消息历史（供 feishu_messages 工具查看）
        recordMessage(event, chatId, text);
        // 异步处理：丢到宿主托管虚拟线程，避免阻塞长连接接收线程；CHAT 调用在此线程的身份作用域内
        ctx.exec().submit(() -> {
            replyText(chatId, "✅ 收到，正在思考…");
            String answer = ctx.chat().ask(text);
            replyText(chatId, (answer == null || answer.isBlank()) ? "（无输出）" : answer);
        });
    }

    /** 回复某会话（默认 chat_id），内部委派 {@link #sendMessage}。 */
    private void replyText(String chatId, String text) {
        String r = sendMessage(chatId, "chat_id", text);
        if (!"已发送".equals(r)) {
            log(r);
        }
    }

    /**
     * 主动发送一条文本消息（以应用机器人身份对话）。
     *
     * @param receiveId 接收方 id
     * @param idType    id 类型：chat_id/open_id/user_id/email（空则 chat_id）
     * @param text      消息文本
     * @return "已发送" 或错误说明
     */
    private String sendMessage(String receiveId, String idType, String text) {
        if (apiClient == null) {
            log("发送失败：apiClient 为空（未配置 appId/secret 或未启用）");
            return "飞书未配置或未连接，无法发送（请在插件中心填写 App ID/Secret 并启用）";
        }
        if (receiveId == null || receiveId.isBlank()) {
            return "缺少 receive_id";
        }
        String type = (idType == null || idType.isBlank()) ? "chat_id" : idType.trim();
        try {
            JsonObject content = new JsonObject();
            content.addProperty("text", text == null ? "" : text);
            CreateMessageResp resp = apiClient.im().v1().message().create(
                    CreateMessageReq.newBuilder()
                            .receiveIdType(type)
                            .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                    .receiveId(receiveId)
                                    .msgType("text")
                                    .content(content.toString())
                                    .uuid(UUID.randomUUID().toString())
                                    .build())
                            .build());
            if (resp.success()) {
                log("已发送 → " + type + "=" + receiveId);
                return "已发送";
            }
            // 关键：把飞书 API 的真实错误码/信息写日志，便于排查（bot 不在会话、缺权限、id 错等）
            String err = "发送失败：code=" + resp.getCode() + ", msg=" + resp.getMsg()
                    + "，requestId=" + resp.getRequestId();
            log(err + "（" + type + "=" + receiveId + "）");
            return err;
        } catch (Exception e) {
            log("发送异常：" + e);
            return "发送异常：" + e.getMessage();
        }
    }

    /** 记录一条收到的消息到有界历史。 */
    private void recordMessage(P2MessageReceiveV1 event, String chatId, String text) {
        String sender = "";
        try {
            var s = event.getEvent().getSender();
            if (s != null && s.getSenderId() != null) {
                sender = s.getSenderId().getOpenId();
            }
        } catch (Exception ignore) {
            // 取发送者失败不影响记录
        }
        String chatType = event.getEvent().getMessage().getChatType();
        String time = fmtTime(event.getEvent().getMessage().getCreateTime());
        history.addLast(new ReceivedMessage(time, chatId, chatType, sender, text));
        while (history.size() > HISTORY_CAP) {
            history.pollFirst();
        }
    }

    /** 把毫秒时间戳字符串格式化为可读本地时间。 */
    private static String fmtTime(String createTimeMs) {
        try {
            long ms = Long.parseLong(createTimeMs);
            return java.time.LocalDateTime
                    .ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return createTimeMs == null ? "" : createTimeMs;
        }
    }

    /**
     * 向聊天编排器贡献两个对话工具（区别于单向通知）：主动发消息、查看收到的消息。
     */
    @Override
    public List<PluginTool> tools() {
        return List.of(
                new PluginTool("feishu_send",
                        "主动向飞书会话/用户发送一条文本消息（以应用机器人身份对话，区别于单向通知 webhook）",
                        List.of(
                                new PluginTool.Param("receive_id", "接收方 id（会话 chat_id 或用户 open_id 等）", true),
                                new PluginTool.Param("id_type", "id 类型：chat_id/open_id/user_id/email，默认 chat_id", false),
                                new PluginTool.Param("text", "消息文本", true)),
                        argsJson -> sendMessage(
                                jsonString(argsJson, "receive_id"),
                                jsonString(argsJson, "id_type"),
                                jsonString(argsJson, "text"))),
                new PluginTool("feishu_messages",
                        "查看用户最近通过飞书发给本应用的消息（含时间/会话 chat_id/发送者 open_id/内容）",
                        List.of(new PluginTool.Param("limit", "返回最近多少条，默认 10、上限 50", false)),
                        argsJson -> formatRecentMessages(jsonInt(argsJson, "limit", 10))));
    }

    /** 格式化最近 N 条收到的消息。 */
    private String formatRecentMessages(int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
        List<ReceivedMessage> all = new ArrayList<>(history);
        if (all.isEmpty()) {
            return "暂无收到的飞书消息（插件启用并连接后，等待用户在飞书中发消息）。";
        }
        List<ReceivedMessage> recent = all.subList(Math.max(0, all.size() - n), all.size());
        StringBuilder sb = new StringBuilder("最近 " + recent.size() + " 条飞书消息：\n");
        for (ReceivedMessage m : recent) {
            sb.append("- [").append(m.time()).append("] chat_id=").append(m.chatId())
                    .append("，sender=").append(m.sender())
                    .append("：").append(m.text()).append("\n");
        }
        return sb.toString();
    }

    /** 用 gson 提取 JSON 对象里某个字符串字段（无则 null）。 */
    private static String jsonString(String json, String key) {
        try {
            var el = JsonParser.parseString(json).getAsJsonObject().get(key);
            return (el == null || el.isJsonNull()) ? null : el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 用 gson 提取 JSON 对象里某个整数字段（无/非法则缺省）。 */
    private static int jsonInt(String json, String key, int def) {
        try {
            var el = JsonParser.parseString(json).getAsJsonObject().get(key);
            return (el == null || el.isJsonNull()) ? def : el.getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 动态注册飞书相关技能（声明式，不落盘）。宿主在 start 后读取并入渐进式暴露，插件卸载时同步移除。
     */
    @Override
    public List<PluginSkill> skills() {
        return List.of(
                new PluginSkill("飞书回复规范",
                        "在飞书即时通讯场景中生成回复时应遵循的风格与约束",
                        SKILL_REPLY_STYLE),
                new PluginSkill("飞书消息格式",
                        "飞书各类消息(文本/富文本/卡片)的内容 JSON 结构与 @ 提及语法参考",
                        SKILL_MESSAGE_FORMAT));
    }

    /** 飞书回复规范技能正文 */
    private static final String SKILL_REPLY_STYLE = """
            # 飞书回复规范

            在飞书(IM)场景回复用户时遵循：

            - **简洁**：飞书是即时通讯，回复控制在 1-3 段，避免长篇大论。
            - **纯文本优先**：普通文本消息不渲染 Markdown 标题/表格/加粗，勿依赖这些排版。
            - **换行用 `\\n`**：分点用 `•` 或 `1. 2. 3.` 等纯文本符号，而非 Markdown 列表。
            - **代码**：短代码可用反引号包裹说明，但文本消息不高亮；大段代码建议提示用户去 IDE 查看。
            - **@提及**：需要点名时在富文本里用 `<at user_id="ou_xxx"></at>`，纯文本消息无法 @。
            - **链接**：直接贴 URL，飞书会自动识别为可点击链接。
            - **不确定时先澄清**：IM 往返成本低，宁可一句话反问，也不要长篇猜测。
            """;

    /** 飞书消息格式技能正文 */
    private static final String SKILL_MESSAGE_FORMAT = """
            # 飞书消息格式

            飞书发送消息时 `msg_type` 与 `content`(JSON 字符串) 的对应：

            - **文本** `msg_type=text`：`{"text":"内容"}`
            - **富文本** `msg_type=post`：
              `{"post":{"zh_cn":{"title":"标题","content":[[{"tag":"text","text":"一段"},{"tag":"a","text":"链接","href":"https://..."}]]}}}`
              （content 是"段落数组的数组"，每行一个数组，行内多个 tag 节点）
            - **交互卡片** `msg_type=interactive`：content 为卡片 JSON，如
              `{"config":{"wide_screen_mode":true},"elements":[{"tag":"div","text":{"tag":"lark_md","content":"**标题**\\n正文"}}]}`
              （卡片里的 `lark_md` 支持有限 Markdown：加粗、链接、换行）

            ## @ 提及语法
            - 富文本/卡片里：`<at user_id="ou_xxx">名字</at>`；@所有人：`<at user_id="all"></at>`

            ## 接收消息内容解析
            - 文本消息 `content` 形如 `{"text":"你好"}`；含 @ 时文本里会出现 `@_user_1` 占位符，处理前应剥离。
            """;

    /** 从消息内容 JSON {@code {"text":"..."}} 取出文本。 */
    private String parseText(String content) {
        try {
            return JsonParser.parseString(content).getAsJsonObject().get("text").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 去除 @机器人/@全体 的占位符。 */
    private String stripMentions(String text) {
        return text.replaceAll("@_user_\\d+", "").replaceAll("@_all", "").trim();
    }

    @Override
    public void stop() {
        log("停用");
        if (wsHandle != null) {
            wsHandle.cancel();
        }
        tryStopWsClient();
    }

    /**
     * 尽力关闭长连接客户端。飞书 oapi-sdk 的 ws.Client 仅公开 {@code start()}，无标准停止 API，
     * 故反射探测常见停止方法；都没有则告警——底层长连接可能持续至应用重启（进程内方案的固有限制，
     * 进程外隔离方案可"杀进程"彻底回收）。
     */
    private void tryStopWsClient() {
        if (wsClient == null) {
            return;
        }
        for (String name : new String[]{"stop", "close", "disconnect", "shutdown"}) {
            try {
                wsClient.getClass().getMethod(name).invoke(wsClient);
                log("已调用 wsClient." + name + "() 关闭长连接");
                return;
            } catch (NoSuchMethodException ignore) {
                // 试下一个
            } catch (Exception e) {
                log("wsClient." + name + "() 调用异常：" + e.getMessage());
            }
        }
        log("⚠ 飞书 SDK 无公开停止方法，底层长连接可能持续至应用重启");
    }

    private void log(String msg) {
        System.out.println("[FeishuPlugin] " + msg);
    }
}
