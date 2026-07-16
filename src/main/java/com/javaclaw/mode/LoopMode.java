package com.javaclaw.mode;

import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Placement;
import com.javaclaw.loop.LoopService;

/**
 * 循环模式的 {@link ConversationMode} 适配器。
 *
 * <p>只承载模式元信息，把 {@link #start} 委派给 {@link LoopService}。循环自身产出的进度/
 * 状态经 {@code ConversationEvent.Progress}/{@code Custom} 复用现有流式事件通道，UI 无需改造。</p>
 */
public final class LoopMode implements ConversationMode {

    private final LoopService service;

    public LoopMode(LoopService service) {
        this.service = service;
    }

    @Override public String id() { return "loop"; }
    @Override public String displayName() { return "🔁 循环"; }
    @Override public String tooltip() { return "自动循环：反复推进同一目标，直到完成或触发停止（首行可写 @loop interval=5m max=20）"; }
    @Override public Placement placement() { return Placement.TOP_SEGMENT; }

    /** 支持流式与取消；暂不处理附件、不直接依赖知识库/浏览器（工具由编排器按需路由）。 */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, true, false, false);
    }

    @Override
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        service.start(request, callbacks);
    }

    @Override
    public boolean cancel() {
        return service.cancelActive();
    }

    @Override
    public void reload() {
        service.reload();
    }

    @Override
    public void shutdown() {
        service.shutdown();
    }
}
