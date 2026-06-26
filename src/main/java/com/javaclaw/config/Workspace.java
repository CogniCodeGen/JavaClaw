package com.javaclaw.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 工作区实体
 *
 * <p>每个工作区拥有独立的配置、数据、日志和浏览器状态。</p>
 *
 * @author JavaClaw
 */
public class Workspace {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String id;
    private String name;
    private String createdAt;

    /** JSON 反序列化用 */
    public Workspace() {}

    /**
     * 创建新工作区
     *
     * @param name 工作区名称
     */
    public Workspace(String name) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.createdAt = LocalDateTime.now().format(FORMATTER);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return name;
    }
}
