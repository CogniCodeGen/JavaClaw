package com.javaclaw.mode;

import com.javaclaw.agent.ChatService;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Placement;

/**
 * 普通聊天模式的 {@link ConversationMode} 适配器。
 *
 * <p>只负责模式元信息（id / displayName / capabilities）和把 {@link #start} 委托给
 * {@link ChatService#streamChat}。服务本身已经直接产出 {@code ConversationEvent}，
 * 这里无需任何翻译逻辑。</p>
 */
public final class ChatMode implements ConversationMode {

    private final ChatService service;

    public ChatMode(ChatService service) {
        this.service = service;
    }

    @Override public String id() { return "chat"; }
    @Override public String displayName() { return "💬 对话"; }
    @Override public String tooltip() { return "普通聊天模式（按计划执行）"; }
    @Override public Placement placement() { return Placement.TOP_SEGMENT; }
    @Override public Capabilities capabilities() { return Capabilities.defaults(); }

    @Override
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        service.streamChat(request, callbacks);
    }

    @Override
    public boolean cancel() {
        return service.cancelStream();
    }

    @Override
    public void clearHistory() {
        service.clearHistory();
    }

    @Override
    public void shutdown() {
        // ChatService.shutdown 由 App 层统一调用
    }
}
