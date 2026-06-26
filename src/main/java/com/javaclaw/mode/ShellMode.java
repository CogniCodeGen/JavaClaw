package com.javaclaw.mode;

import com.javaclaw.agent.ShellCommandService;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Placement;

/**
 * 命令（Shell）模式 —— 用确定性命令管理长任务 / 智能体 / 定时工作，不走 LLM。
 *
 * <p>薄封装：把 {@link #start} 委托给 {@link ShellCommandService#handle}。命令清单见 {@code /help}。
 * 与对话内的 task_manage/schedule/agent @Tool 是同一批 Manager 的两条入口。</p>
 */
public final class ShellMode implements ConversationMode {

    private final ShellCommandService service;

    public ShellMode(ShellCommandService service) {
        this.service = service;
    }

    @Override public String id() { return "shell"; }
    @Override public String displayName() { return "⌘ 命令"; }
    @Override public String tooltip() { return "命令模式：/task /agent /schedule 管理长任务、智能体、定时工作（输入 /help 查看）"; }
    @Override public Placement placement() { return Placement.TOP_SEGMENT; }
    @Override public Capabilities capabilities() { return Capabilities.minimal(); }

    @Override
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        service.handle(request, callbacks);
    }
}
