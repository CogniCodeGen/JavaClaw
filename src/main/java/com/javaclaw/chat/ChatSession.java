package com.javaclaw.chat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天会话模型
 *
 * <p>每个会话拥有独立的 ID、标题、消息列表和创建时间，
 * 支持多会话并行管理和切换。</p>
 *
 * @author JavaClaw
 */
public class ChatSession {

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 会话唯一标识 */
    private final String id;

    /** 会话标题 */
    private String title;

    /** 会话创建时间 */
    private final LocalDateTime createdAt;

    /** 会话内的聊天消息列表 */
    private final List<ChatMessage> messages;

    /**
     * 创建新会话
     */
    public ChatSession(String title) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.title = title;
        this.createdAt = LocalDateTime.now();
        this.messages = new ArrayList<>();
    }

    /**
     * 从持久化数据恢复会话
     */
    public ChatSession(String id, String title, LocalDateTime createdAt, List<ChatMessage> messages) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    /**
     * 获取格式化的创建时间（如 "03-26 14:30"）
     */
    public String getFormattedTime() {
        return createdAt.format(DISPLAY_FORMATTER);
    }

    /**
     * 根据第一条用户消息自动生成标题
     *
     * <p>截取用户首条消息的前 20 个字符作为会话标题。</p>
     */
    public void autoTitle() {
        for (ChatMessage msg : messages) {
            if (msg.getRole() == ChatMessage.Role.USER && !msg.getContent().isBlank()) {
                String content = msg.getContent().trim();
                this.title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
                return;
            }
        }
    }
}
