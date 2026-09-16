package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;

/** 仅依据唯一 Core 调用证据提取 Site 工具附件，不把任意 JSON 字段解释成图片地址。 */
final class BrowserToolAttachments {
    private BrowserToolAttachments() {}

    static List<AttachmentRef> from(CanonicalJson json, CorePayloads.ToolResult result, Optional<ItemEnvelope> call) {
        if (!result.success() || call.isEmpty()) {
            return List.of();
        }
        CorePayloads.ToolCall source = json.decode(call.orElseThrow().payload(), CorePayloads.ToolCall.class);
        if (!BuiltinExtensionIds.SITE.equals(source.producerId())
                || !BrowserCommands.TOOL_NAMES.contains(source.toolName())) {
            return List.of();
        }
        try {
            return json.decode(result.output(), BrowserResult.class).attachment().stream()
                    .toList();
        } catch (RuntimeException malformed) {
            // 非版本化结果保留原始详情，但不能生成具有平台预览权限的附件入口。
            return List.of();
        }
    }
}
