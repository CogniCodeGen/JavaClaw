package com.javaclaw.mode;

import com.javaclaw.agent.PlanModeService;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Placement;

/**
 * 规划模式的 {@link ConversationMode} 适配器。
 *
 * <p>只负责模式元信息和把 {@link #start} 委托给 {@link PlanModeService#planChat}。
 * 底层服务直接产出 {@code ConversationEvent}，本类无需任何事件翻译。</p>
 */
public final class PlanMode implements ConversationMode {

    private final PlanModeService service;

    public PlanMode(PlanModeService service) {
        this.service = service;
    }

    @Override public String id() { return "plan"; }
    @Override public String displayName() { return "⤳ 研讨"; }
    @Override public String tooltip() { return "研讨模式（多专家讨论汇总方案）"; }
    @Override public Placement placement() { return Placement.TOP_SEGMENT; }

    @Override
    public Capabilities capabilities() {
        // 规划模式没有循环检测 / GEPA 评估，但支持附件、流式、取消、知识库
        return new Capabilities(true, true, true, true, false);
    }

    @Override
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        service.planChat(request, callbacks);
    }

    @Override
    public boolean cancel() {
        return service.cancel();
    }

    @Override
    public void clearHistory() {
        service.clearHistory();
    }

    @Override
    public void shutdown() {
        // PlanModeService.shutdown 由 App 层统一调用
    }
}
