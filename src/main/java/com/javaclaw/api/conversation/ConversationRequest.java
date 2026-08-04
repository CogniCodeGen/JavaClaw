package com.javaclaw.api.conversation;

import com.javaclaw.util.ProjectAccessPolicy;

import java.io.File;
import java.util.ArrayList;
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
 * @param sessionId   当前聊天会话 ID；旧调用方可通过双参数构造器省略
 * @param options     本轮行为选项
 */
public record ConversationRequest(String userInput, List<File> attachments, String sessionId,
                                  ConversationOptions options) {

    public ConversationRequest {
        if (userInput == null) userInput = "";
        if (attachments == null) {
            attachments = List.of();
        } else {
            List<File> safeAttachments = new ArrayList<>(attachments.size());
            for (File attachment : attachments) {
                if (attachment == null) {
                    throw new IllegalArgumentException("附件不能为空");
                }
                try {
                    safeAttachments.add(ProjectAccessPolicy.requireProjectFilePath(
                            attachment.toPath()).toFile());
                } catch (SecurityException e) {
                    throw new IllegalArgumentException("严格项目隔离已拒绝项目外附件", e);
                }
            }
            attachments = List.copyOf(safeAttachments);
        }
        if (sessionId != null && sessionId.isBlank()) sessionId = null;
        if (options == null) options = ConversationOptions.DEFAULT;
    }

    public ConversationRequest(String userInput, List<File> attachments, String sessionId) {
        this(userInput, attachments, sessionId, ConversationOptions.DEFAULT);
    }

    public ConversationRequest(String userInput, List<File> attachments) {
        this(userInput, attachments, null, ConversationOptions.DEFAULT);
    }

    /** 仅含文本的请求（常见简单场景） */
    public static ConversationRequest ofText(String userInput) {
        return new ConversationRequest(userInput, List.of(), null, ConversationOptions.DEFAULT);
    }
}
