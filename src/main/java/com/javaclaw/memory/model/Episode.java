package com.javaclaw.memory.model;

/**
 * 情景记忆 —— 一次对话轮 / 事件（发生过什么、何时）。
 *
 * <p>每轮对话结束后廉价落地（无 LLM）；{@link #embedding} 供按相关性 + 近因召回历史事件。
 * 这是旧系统完全缺失的一层（旧对话历史只活在会话内存里）。</p>
 *
 * @author JavaClaw
 */
public class Episode {

    public String id;
    public long entityId;

    public String sessionId;
    public String userInput;
    public String assistantReply;

    /** 工具调用轨迹摘要（JSON 文本），便于回溯"当时做了什么" */
    public String toolTraceJson;

    /** 预计算向量（基于 userInput + 摘要） */
    public float[] embedding;

    public long timestamp;

    public Episode() {}

    public Episode(String sessionId, String userInput, String assistantReply) {
        this.sessionId = sessionId;
        this.userInput = userInput;
        this.assistantReply = assistantReply;
    }
}
