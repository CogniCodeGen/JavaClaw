package com.javaclaw.protocol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Skill 的声明内容及启用状态，不是可加载到主 JVM 的代码。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param version 组件或声明的版本字符串
 * @param manifest 结构化 Skill 声明内容
 * @param enabled 是否启用；禁用不会删除历史记录
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record WireSkill(
        String id,
        String name,
        String version,
        JsonNode manifest,
        boolean enabled,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
