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

    private static final String DEFAULT_TITLE = "新的对话";

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 会话唯一标识 */
    private final String id;

    /** 会话标题 */
    private String title;
    private boolean autoTitlePending;

    /** 会话创建时间 */
    private final LocalDateTime createdAt;

    /** 会话内的聊天消息列表 */
    private final List<ChatMessage> messages;
    private boolean archived;
    private String parentThreadId;

    /**
     * 创建新会话
     */
    public ChatSession(String title) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.createdAt = LocalDateTime.now();
        this.messages = new ArrayList<>();
    }

    /** A new conversation explicitly awaiting its first user-authored title. */
    public static ChatSession untitled() {
        ChatSession session = new ChatSession(DEFAULT_TITLE);
        session.autoTitlePending = true;
        return session;
    }

    /**
     * 从持久化数据恢复会话
     */
    public ChatSession(String id, String title, LocalDateTime createdAt, List<ChatMessage> messages) {
        this(id, title, createdAt, messages, false);
    }

    public ChatSession(String id, String title, LocalDateTime createdAt,
                       List<ChatMessage> messages, boolean autoTitlePending) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.autoTitlePending = autoTitlePending;
    }

    public String getId() {
        return id;
    }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean value) { archived = value; }
    public String getParentThreadId() { return parentThreadId; }
    public void setParentThreadId(String value) { parentThreadId = value; }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.autoTitlePending = false;
    }

    public boolean isAutoTitlePending() { return autoTitlePending; }

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
     * 根据第一条用户消息为尚未命名的会话生成标题。
     *
     * <p>截取用户首条消息的前 20 个字符；保留已有标题（包括分支标题）。</p>
     *
     * @return 标题是否发生变化
     */
    public boolean autoTitle() {
        if (!autoTitlePending) return false;
        for (ChatMessage msg : messages) {
            if (msg.getRole() == ChatMessage.Role.USER
                    && msg.getContent() != null && !msg.getContent().isBlank()) {
                String content = msg.getContent().trim();
                this.title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
                this.autoTitlePending = false;
                return true;
            }
        }
        return false;
    }
}
