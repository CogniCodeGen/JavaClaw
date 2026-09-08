package com.javaclaw.server.extension;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ItemEvidencePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreCommandService;

/** 通过 Core 权威 Item 日志核验学习证据，不接受客户端自报正文作为事实来源。 */
public final class CoreItemEvidencePort implements ItemEvidencePort {
    private final CoreCommandService core;
    private final CanonicalJson json;

    /**
     * 创建证据端口。
     *
     * @param core Core 查询服务
     * @param json 规范 JSON codec
     */
    public CoreItemEvidencePort(CoreCommandService core, CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public boolean containsVerbatim(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId, String verbatim) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(itemId, "itemId");
        String checked = Objects.requireNonNull(verbatim, "verbatim");
        if (checked.isEmpty()) {
            return false;
        }
        return find(workspaceId, threadId, itemId)
                .filter(item -> containsEvidence(item, checked))
                .isPresent();
    }

    private boolean containsEvidence(ItemEnvelope item, String verbatim) {
        if (CoreSchemas.MESSAGE.equals(item.schemaId())) {
            // 消息证据只能来自公开正文；角色、附件名称与 MIME 等元数据不能被自动学习为用户原话。
            return json.decode(item.payload(), CorePayloads.Message.class)
                    .text()
                    .contains(verbatim);
        }
        return contains(json.decode(item.payload(), Object.class), verbatim);
    }

    @Override
    public boolean isUncertainOutcome(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId) {
        return find(workspaceId, threadId, itemId).map(this::isUncertain).orElse(true);
    }

    @Override
    public boolean isUserText(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId) {
        return find(workspaceId, threadId, itemId)
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId())
                        && "core".equals(item.producerId())
                        && item.status() == ItemStatus.COMPLETED)
                .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                .filter(message -> message.role() == MessageRole.USER)
                .isPresent();
    }

    private Optional<ItemEnvelope> find(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId) {
        var thread = core.findThread(threadId);
        if (thread.isEmpty() || !thread.orElseThrow().workspaceId().equals(workspaceId)) {
            return Optional.empty();
        }
        return core.listItems(threadId).stream()
                .filter(item -> item.id().equals(itemId))
                .findFirst();
    }

    private boolean isUncertain(ItemEnvelope item) {
        if (CoreSchemas.ERROR.equals(item.schemaId())) {
            return true;
        }
        if (!CoreSchemas.TOOL_RESULT.equals(item.schemaId())) {
            return false;
        }
        return !json.decode(item.payload(), CorePayloads.ToolResult.class).success();
    }

    private static boolean contains(Object value, String verbatim) {
        if (value instanceof String text) {
            return text.contains(verbatim);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(child -> contains(child, verbatim));
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(child -> contains(child, verbatim));
        }
        return false;
    }
}
