package com.javaclaw.chat;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聊天消息模型类
 *
 * <p>表示聊天界面中的一条消息，包含角色（用户/助手/系统）、
 * 消息内容、附件列表和时间戳。用于在 UI 层渲染消息气泡。</p>
 *
 * @author JavaClaw
 */
public class ChatMessage {

    /** 时间格式化器：显示"时:分" */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 消息角色枚举
     */
    public enum Role {
        /** 用户发送的消息 */
        USER("用户"),
        /** 助手回复的消息 */
        ASSISTANT("助手"),
        /** 系统提示消息（如错误信息） */
        SYSTEM("系统");

        private final String displayName;

        Role(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** 支持的图片扩展名 */
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp"
    );

    /** 支持的文本文档扩展名 */
    private static final List<String> TEXT_DOC_EXTENSIONS = List.of(
            "txt", "md", "csv", "json", "xml", "html", "css",
            "java", "py", "js", "ts", "c", "cpp", "h", "go", "rs", "log", "yaml", "yml"
    );

    /** 消息角色 */
    private final Role role;

    /** 消息文本内容 */
    private final String content;

    /** 消息创建时间 */
    private final LocalDateTime timestamp;

    /** 附件文件列表 */
    private final List<File> attachments;

    /** 消息关联的图片文件路径（如浏览器截图、系统截图），持久化到会话文件 */
    private final List<String> imagePaths;

    /** 用户是否已采纳此助手回复（仅对 ASSISTANT 消息有意义），持久化到会话文件 */
    private boolean adopted;

    /**
     * 构造一条聊天消息（时间戳自动设为当前时间，无附件）
     */
    public ChatMessage(Role role, String content) {
        this(role, content, LocalDateTime.now(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 构造一条聊天消息（指定时间戳，用于从历史记录加载，无附件）
     */
    public ChatMessage(Role role, String content, LocalDateTime timestamp) {
        this(role, content, timestamp, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 构造一条聊天消息（指定时间戳和图片路径，用于从历史记录加载）
     */
    public ChatMessage(Role role, String content, LocalDateTime timestamp, List<String> imagePaths) {
        this(role, content, timestamp, Collections.emptyList(), imagePaths);
    }

    /**
     * 构造一条带附件的聊天消息
     */
    public ChatMessage(Role role, String content, List<File> attachments) {
        this(role, content, LocalDateTime.now(), attachments, Collections.emptyList());
    }

    /**
     * 全参数构造
     */
    public ChatMessage(Role role, String content, LocalDateTime timestamp,
                       List<File> attachments, List<String> imagePaths) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.attachments = attachments != null ? new ArrayList<>(attachments) : Collections.emptyList();
        this.imagePaths = imagePaths != null ? new ArrayList<>(imagePaths) : new ArrayList<>();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public List<File> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public List<String> getImagePaths() {
        return Collections.unmodifiableList(imagePaths);
    }

    /**
     * 添加图片路径（流式输出过程中收集）
     */
    public void addImagePath(String path) {
        if (path != null && !path.isEmpty() && !imagePaths.contains(path)) {
            imagePaths.add(path);
        }
    }

    public boolean isAdopted() {
        return adopted;
    }

    public void setAdopted(boolean adopted) {
        this.adopted = adopted;
    }

    /**
     * 获取格式化的时间字符串（如 "14:30"）
     */
    public String getFormattedTime() {
        return timestamp.format(TIME_FORMATTER);
    }

    /**
     * 判断文件是否为支持的图片格式
     */
    public static boolean isImageFile(File file) {
        String ext = getFileExtension(file).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 判断文件是否为支持的文本文档格式
     */
    public static boolean isTextDocument(File file) {
        String ext = getFileExtension(file).toLowerCase();
        return TEXT_DOC_EXTENSIONS.contains(ext);
    }

    /**
     * 判断文件是否为 PDF 文档
     */
    public static boolean isPdfFile(File file) {
        return "pdf".equalsIgnoreCase(getFileExtension(file));
    }

    /**
     * 获取文件扩展名
     */
    public static String getFileExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : "";
    }
}
