package com.javaclaw.memory.model;

/** Durable working-memory snapshot associated with one conversation or agent key. */
public class AgentCheckpoint {
    public String key;
    public String messagesJson;
    public long updatedAt;

    public AgentCheckpoint() {}

    public AgentCheckpoint(String key, String messagesJson) {
        this.key = key;
        this.messagesJson = messagesJson;
        this.updatedAt = System.currentTimeMillis();
    }
}
