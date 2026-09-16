package com.javaclaw.server.preview;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreItemReader;
import com.javaclaw.server.turn.BrowserImageProjection;
import com.javaclaw.server.turn.PreviewReadAuthority;

/** 浏览器预览仍绑定已提交 Item、原 ToolCall 和 Thread，不降级为仅 Workspace 的附件访问。 */
final class BrowserPreviewSource {
    private final CoreItemReader core;
    private final PreviewReadAuthority authority;
    private final CanonicalJson json;

    BrowserPreviewSource(CoreItemReader core, PreviewReadAuthority authority, CanonicalJson json) {
        this.core = core;
        this.authority = authority;
        this.json = json;
    }

    void require(DocumentReference reference, ItemEnvelope item) {
        if (!CoreSchemas.TOOL_RESULT.equals(item.schemaId())
                || !"core".equals(item.producerId())
                || item.status() != ItemStatus.COMPLETED) {
            throw new SecurityException("浏览器附件缺少已提交的 Core 结果来源");
        }
        CorePayloads.ToolResult output = json.decode(item.payload(), CorePayloads.ToolResult.class);
        CorePayloads.ToolCall call =
                core.findPriorToolCall(item, output.callId()).orElseThrow(() -> new SecurityException("浏览器附件缺少工具调用证据"));
        if (!output.success()
                || !BuiltinExtensionIds.SITE.equals(call.producerId())
                || !BrowserCommands.TOOL_NAMES.contains(call.toolName())) {
            throw new SecurityException("附件来源不是成功的 Site 浏览器调用");
        }
        var thread = authority.sourceThread(reference.workspaceId(), item.id());
        BrowserResult result = json.decode(output.output(), BrowserResult.class);
        var owner = result.observation().session().owner();
        if (!owner.workspaceId().equals(reference.workspaceId())
                || !owner.threadId().equals(thread)
                || !result.attachment().equals(reference.attachment())) {
            throw new SecurityException("浏览器附件与来源 Thread 或提交元数据不一致");
        }
        BrowserImageProjection.project(
                json,
                reference.workspaceId(),
                thread,
                new ToolIdentity(call.producerId(), call.toolName(), call.toolRevision()),
                output.output());
    }
}
