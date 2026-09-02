package com.javaclaw.desktop.view;

import java.util.Objects;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.protocol.CanonicalJson;

/** 只按 Core schema 呈现 Transcript；未知扩展 payload 原样保留但不执行。 */
public final class TranscriptPresenter {
    private final CanonicalJson json;

    /**
     * 创建 Item presenter。
     *
     * @param json 共享 core codec
     */
    public TranscriptPresenter(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 映射一个 Item。
     *
     * @param item 持久化信封
     * @return 可访问文本投影
     */
    public PresentedItem present(ItemEnvelope item) {
        return switch (item.schemaId()) {
            case CoreSchemas.MESSAGE -> message(item);
            case CoreSchemas.TOOL_CALL -> toolCall(item);
            case CoreSchemas.TOOL_RESULT -> toolResult(item);
            case CoreSchemas.APPROVAL -> approval(item);
            case CoreSchemas.ERROR -> error(item);
            default -> new PresentedItem(item.kind(), item.payload().json(), "transcript-execution-block");
        };
    }

    private PresentedItem message(ItemEnvelope item) {
        CorePayloads.Message payload = json.decode(item.payload(), CorePayloads.Message.class);
        String style =
                switch (payload.role()) {
                    case USER -> "message-user";
                    case ASSISTANT -> "message-assistant";
                    case SYSTEM, TOOL -> "transcript-execution-block";
                };
        return new PresentedItem(payload.role().name(), payload.text(), style);
    }

    private PresentedItem toolCall(ItemEnvelope item) {
        CorePayloads.ToolCall payload = json.decode(item.payload(), CorePayloads.ToolCall.class);
        return new PresentedItem(
                "工具 · " + payload.toolName(),
                "来源 " + payload.producerId() + " · revision " + payload.toolRevision(),
                "transcript-execution-block");
    }

    private PresentedItem toolResult(ItemEnvelope item) {
        CorePayloads.ToolResult payload = json.decode(item.payload(), CorePayloads.ToolResult.class);
        String title = payload.success() ? "工具结果" : "工具未执行";
        return new PresentedItem(title, payload.output().json(), "transcript-execution-block");
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
