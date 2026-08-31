package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 用户输入及其内容寻址附件；客户端无需解析原始 JSON 即可展示历史附件。
 *
 * @param text 原始用户文本；仅附件消息可以为空
 * @param attachments 附件的摘要、媒体类型和展示名，不包含服务器或客户端路径
 * @param document 保留未知扩展的原始文档
 */
public record UserMessageItemContent(String text, List<TurnInput.Attachment> attachments, JsonDocument document)
        implements ItemContent {
    /** 固定输入列表，历史附件不随 UI 编辑变化。 */
    public UserMessageItemContent {
        text = java.util.Objects.requireNonNull(text);
        attachments = List.copyOf(attachments);
    }

    @Override
    public String kind() {
        return "userMessage";
    }
}
