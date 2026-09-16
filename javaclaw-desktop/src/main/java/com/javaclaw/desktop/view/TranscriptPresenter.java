package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.MessageRole;
import com.javaclaw.client.extension.CodingToolResultIndex;
import com.javaclaw.client.extension.CodingTranscriptFormatter;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.protocol.CanonicalJson;

/** 按 Core 与 Coding 版本化 schema 呈现 Transcript；未知扩展 payload 原样保留但不执行。 */
public final class TranscriptPresenter {
    private final CanonicalJson json;
    private final CodingTranscriptFormatter coding;
    private final CodingToolResultIndex codingCalls;

    /**
     * 创建 Item presenter。
     *
     * @param json 共享 core codec
     */
    public TranscriptPresenter(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
        coding = new CodingTranscriptFormatter(json);
        codingCalls = new CodingToolResultIndex(json);
    }

    /**
     * 在虚拟化列表绘制前装载完整快照的调用关联，避免依赖 Cell 绘制顺序。
     *
     * @param items 当前会话已加载的持久 Item
     */
    public void replaceItems(List<ItemEnvelope> items) {
        codingCalls.clear();
        items.forEach(codingCalls::accept);
    }

    /**
     * 映射一个 Item。
     *
     * @param item 持久化信封
     * @return 可访问文本投影
     */
    public PresentedItem present(ItemEnvelope item) {
        if (CoreSchemas.TOOL_RESULT.equals(item.schemaId())) {
            return toolResult(item);
        }
        var fact = coding.format(item, codingCalls.callFor(item));
        if (fact.isPresent()) {
            return new PresentedItem(fact.get().title(), fact.get().body(), "transcript-execution-block");
        }
        return switch (item.schemaId()) {
            case CoreSchemas.MESSAGE -> message(item);
            case CoreSchemas.TOOL_CALL -> toolCall(item);
            case CoreSchemas.APPROVAL -> approval(item);
            case CoreSchemas.ERROR -> error(item);
            default -> fallback(item);
        };
    }

    /**
     * 将服务端有界历史摘要映射到与完整事实相同的标题和样式；不把摘要还原为完整 Item。
     *
     * @param entry 保留身份和来源的历史条目
     * @return WebView 与原生简版共用的纯文本展示
     */
    public static PresentedItem presentHistory(ItemHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.role().isPresent()) {
            MessageRole role = entry.role().orElseThrow();
            String style =
                    switch (role) {
                        case USER -> "message-user";
                        case ASSISTANT -> "message-assistant";
                        case SYSTEM, TOOL -> "transcript-execution-block";
                    };
            return new PresentedItem(roleLabel(role), entry.summary(), style);
        }
        String style =
                switch (entry.kind()) {
                    case "error" -> "transcript-error-block";
                    case "approval", "input" -> "transcript-interaction-block";
                    default -> "transcript-execution-block";
                };
        int newline = entry.summary().indexOf('\n');
        PresentedItem text = newline < 0
                ? new PresentedItem(entry.kind(), entry.summary(), style)
                : new PresentedItem(
                        entry.summary().substring(0, newline), entry.summary().substring(newline + 1), style);
        return historyTool(entry.kind(), text);
    }

    private static PresentedItem historyTool(String kind, PresentedItem text) {
        // 历史接口只携带摘要；类型与服务端成功标题共同决定折叠，失败和交互不能被正文标题伪装隐藏。
        boolean call = kind.equals("tool-call") && text.title().startsWith("工具 · ");
        boolean success = kind.equals("tool-result") && text.title().equals("工具结果 · 成功");
        if (call || success) {
            return new PresentedItem(
                    text.title(),
                    "",
                    text.styleClass(),
                    text.body(),
                    !text.body().isEmpty());
        }
        if (kind.equals("tool-result") && text.title().equals("工具结果 · 失败或未完成")) {
            return new PresentedItem(text.title(), text.body(), "transcript-error-block");
        }
        return text;
    }

    private static String roleLabel(MessageRole role) {
        return switch (role) {
            case USER -> "你";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            case TOOL -> "工具";
        };
    }

    /**
     * 呈现尚未被权威历史接替的本地用户消息，不将回执丢失解释为发送成功或确定失败。
     *
     * @param message 当前会话的非空发送状态
     * @return 保留原文、用户样式和确认状态的纯文本展示
     */
    public static PresentedItem presentOutgoing(OutgoingMessage message) {
        String status =
                switch (message.status()) {
                    case SENDING -> "正在发送…";
                    case ACCEPTED -> "已发送";
                    case UNCONFIRMED -> "发送未确认";
                };
        return new PresentedItem("你 · " + status, message.text(), "message-user");
    }

    private PresentedItem fallback(ItemEnvelope item) {
        var fallback = coding.fallback(item);
        return new PresentedItem(fallback.title(), fallback.body(), "transcript-execution-block");
    }

    private PresentedItem message(ItemEnvelope item) {
        CorePayloads.Message payload = json.decode(item.payload(), CorePayloads.Message.class);
        String style =
                switch (payload.role()) {
                    case USER -> "message-user";
                    case ASSISTANT -> "message-assistant";
                    case SYSTEM, TOOL -> "transcript-execution-block";
                };
        return new PresentedItem(roleLabel(payload.role()), payload.text(), style);
    }

    private PresentedItem toolCall(ItemEnvelope item) {
        CorePayloads.ToolCall payload = json.decode(item.payload(), CorePayloads.ToolCall.class);
        return new PresentedItem(
                "工具 · " + payload.toolName() + " · 已调用",
                "",
                "transcript-execution-block",
                "来源 " + payload.producerId() + " · revision " + payload.toolRevision() + "\n调用：" + payload.callId()
                        + "\n参数：" + payload.arguments().json(),
                true);
    }

    private PresentedItem toolResult(ItemEnvelope item) {
        CorePayloads.ToolResult payload = json.decode(item.payload(), CorePayloads.ToolResult.class);
        var call = codingCalls.callFor(item);
        String name = call.map(value -> json.decode(value.payload(), CorePayloads.ToolCall.class)
                        .toolName())
                .orElse("工具结果");
        String output = coding.format(item, call)
                .map(fact -> fact.title() + "\n" + fact.body())
                .orElseGet(() -> coding.fallback(item).body());
        String title = name + (payload.success() ? " · 成功" : " · 失败或未完成");
        String metadata = "调用：" + payload.callId();
        return new PresentedItem(
                title,
                payload.success() ? "" : output,
                payload.success() ? "transcript-execution-block" : "transcript-error-block",
                metadata + (payload.success() ? "\n" + output : ""),
                true,
                BrowserToolAttachments.from(json, payload, call));
    }

    private PresentedItem approval(ItemEnvelope item) {
        CorePayloads.Approval payload = json.decode(item.payload(), CorePayloads.Approval.class);
        return new PresentedItem(
                "审批 · " + payload.toolName(),
                payload.state() + " · " + payload.reason(),
                "transcript-interaction-block");
    }

    private PresentedItem error(ItemEnvelope item) {
        CorePayloads.Error payload = json.decode(item.payload(), CorePayloads.Error.class);
        return new PresentedItem(payload.code(), payload.message(), "transcript-error-block");
    }
}
