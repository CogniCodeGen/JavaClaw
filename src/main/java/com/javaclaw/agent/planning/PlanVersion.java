package com.javaclaw.agent.planning;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 执行计划版本快照：记录计划内容、版本号和变更原因
 */
public class PlanVersion {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int version;
    private final String content;
    private final String changeReason;
    private final String createdAt;

    public PlanVersion(int version, String content, String changeReason) {
        this.version = version;
        this.content = content != null ? content : "";
        this.changeReason = changeReason != null ? changeReason : "";
        this.createdAt = LocalDateTime.now().format(FORMATTER);
    }

    public int getVersion() { return version; }
    public String getContent() { return content; }
    public String getChangeReason() { return changeReason; }
    public String getCreatedAt() { return createdAt; }
}
