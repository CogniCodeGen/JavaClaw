package com.javaclaw.server.extension;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.server.persistence.CommandIdentity;

/** 当前 Thread 附件白名单与 Browser 输出存储；摘要、文件名都不具有宿主路径语义。 */
final class BrowserThreadAttachments {
    private final SiteBrowserHostContext host;

    BrowserThreadAttachments(SiteBrowserHostContext host) {
        this.host = host;
    }

    AttachmentRef upload(WorkspaceId workspace, ThreadId thread, String digest) {
        if (!host.core().workspaceForThread(thread).id().equals(workspace)) {
            throw new SecurityException("上传附件 Thread 不属于 Workspace");
        }
        List<ItemEnvelope> items = host.core().listItems(thread);
        Map<CallKey, CallSource> calls = calls(items);
        for (var item : items) {
            if (item.status() != ItemStatus.COMPLETED || !"core".equals(item.producerId())) {
                continue;
            }
            List<AttachmentRef> references = List.of();
            if (CoreSchemas.MESSAGE.equals(item.schemaId())) {
                references = host.json()
                        .decode(item.payload(), CorePayloads.Message.class)
                        .attachments();
            } else if (CoreSchemas.TOOL_RESULT.equals(item.schemaId())) {
                references = resultAttachments(workspace, thread, item, calls);
            }
            Optional<AttachmentRef> found = references.stream()
                    .filter(reference -> reference.digest().equals(digest))
                    .findFirst();
            if (found.isPresent()) {
                host.attachments().requireOwned(workspace, found.orElseThrow());
                return found.orElseThrow();
            }
        }
        throw new SecurityException("上传只允许当前对话已拥有的附件");
    }

    private Map<CallKey, CallSource> calls(List<ItemEnvelope> items) {
        Map<CallKey, CallSource> calls = new LinkedHashMap<>();
        Set<CallKey> duplicate = new HashSet<>();
        for (var item : items) {
            if (!"core".equals(item.producerId()) || !CoreSchemas.TOOL_CALL.equals(item.schemaId())) {
                continue;
            }
            var call = host.json().decode(item.payload(), CorePayloads.ToolCall.class);
            CallKey key = new CallKey(item.turnId(), call.callId());
            if (calls.putIfAbsent(key, new CallSource(item.sequence(), item.status(), call)) != null) {
                duplicate.add(key);
            }
        }
        // 先索引整个历史窗口再使用结果；后到的同 Turn 重复身份也不能使较早结果提前获准。
        duplicate.forEach(calls::remove);
        return calls;
    }

    private List<AttachmentRef> resultAttachments(
            WorkspaceId workspace, ThreadId thread, ItemEnvelope item, Map<CallKey, CallSource> calls) {
        var result = host.json().decode(item.payload(), CorePayloads.ToolResult.class);
        var source = calls.get(new CallKey(item.turnId(), result.callId()));
        if (!result.success()
                || source == null
                || source.sequence() >= item.sequence()
                || source.status() != ItemStatus.COMPLETED
                || !BuiltinExtensionIds.SITE.equals(source.call().producerId())
                || !BrowserCommands.TOOL_NAMES.contains(source.call().toolName())) {
            return List.of();
        }
        try {
            BrowserResult output = host.json().decode(result.output(), BrowserResult.class);
            var owner = output.observation().session().owner();
            if (!owner.workspaceId().equals(workspace) || !owner.threadId().equals(thread)) {
                return List.of();
            }
            return output.attachment().stream().toList();
        } catch (com.javaclaw.protocol.ProtocolException | IllegalArgumentException malformed) {
            // 待授权或不完整观察不产生附件能力，也不阻断后续消息中的合法附件。
            return List.of();
        }
    }

    private record CallKey(TurnId turnId, String callId) {}

    private record CallSource(long sequence, ItemStatus status, CorePayloads.ToolCall call) {}

    BrowserResult store(
            BrowserSessionState session, BrowserSessionState.Access access, String key, BrowserActionResult result) {
        requireCurrent(session, access, result);
        Optional<AttachmentRef> attachment = result.observation().artifact().map(artifact -> {
            byte[] content = result.content();
            try {
                var identity = new CommandIdentity(
                        "site/browser/attachment",
                        key + ":attachment",
                        0,
                        host.json()
                                .encode(Map.of("session", session.id, "artifact", artifact))
                                .sha256());
                var metadata = host.attachments()
                        .store(
                                AttachmentScope.workspace(session.owner.workspaceId()),
                                identity,
                                artifact.file().mediaType(),
                                content);
                return new AttachmentRef(
                        metadata.digest(), metadata.mediaType(), artifact.file().fileName(), metadata.sizeBytes());
            } finally {
                Arrays.fill(content, (byte) 0);
            }
        });
        synchronized (session) {
            requireCurrent(session, access, result);
            session.view = result.observation().session();
            session.touchedAt = host.clock().instant();
        }
        return new BrowserResult(1, result.observation(), attachment);
    }

    private void requireCurrent(
            BrowserSessionState session, BrowserSessionState.Access access, BrowserActionResult result) {
        var observed = result.observation().session();
        if (session.closed
                || session.closing
                || access != session.access
                || access.cancelled().isCancelled()
                || !access.lease().active(host.clock().instant())
                || !observed.sessionId().equals(session.id)
                || !observed.owner().equals(session.owner)
                || !observed.lease().equals(access.lease())) {
            throw new SecurityException("浏览器观察已过期或不属于当前会话");
        }
        result.observation().frame().ifPresent(frame -> {
            if (frame.controlGeneration() != access.lease().generation()
                    || !frame.pageId().equals(result.observation().page().pageId())) {
                throw new SecurityException("浏览器截图不属于当前页面或控制代次");
            }
        });
    }
}
