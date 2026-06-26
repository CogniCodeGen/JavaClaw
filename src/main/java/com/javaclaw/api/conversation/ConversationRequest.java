package com.javaclaw.api.conversation;

import java.io.File;
import java.util.List;

/**
 * 对话请求。
 *
 * <p>{@link ConversationMode#start(ConversationRequest, ConversationCallbacks)} 的入参，
 * 统一承载用户输入 + 附件 + 本次请求的元信息。未来扩展字段（引用消息、图片 URL 等）时
 * 以不破坏兼容性为原则。</p>
 *
 * @param userInput   用户输入的原始文本（未经知识库增强）
 * @param attachments 附件文件列表（可为空），由模式按自身能力决定是否使用
 */
public record ConversationRequest(String userInput, List<File> attachments) {

    public ConversationRequest {
        if (userInput == null) userInput = "";
        if (attachments == null) attachments = List.of();
    }

    /** 仅含文本的请求（常见简单场景） */
    public static ConversationRequest ofText(String userInput) {
        return new ConversationRequest(userInput, List.of());
    }
}
