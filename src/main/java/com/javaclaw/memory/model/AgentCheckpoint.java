package com.javaclaw.memory.model;

/**
 * 工作记忆检查点 —— 某会话/智能体上下文窗口的快照（替代旧 agent-sessions JsonSession + 内存快照 Map）。
 *
 * <p>刻意以 JSON 文本承载消息列表,而非直接持久化 AgentScope {@code Msg} 对象图 ——
 * 避免与 AgentScope 内部类形状耦合,降低 EclipseStore schema 演进负担。
 * 序列化/反序列化由集成层(P3)负责。</p>
 *
 * @author JavaClaw
 */
public class AgentCheckpoint {

    /** 会话或智能体标识 */
    public String key;

    /** 消息列表的 JSON 表示 */
    public String messagesJson;

    public long updatedAt;

    public AgentCheckpoint() {}

    public AgentCheckpoint(String key, String messagesJson) {
        this.key = key;
        this.messagesJson = messagesJson;
        this.updatedAt = System.currentTimeMillis();
    }
}
