package com.javaclaw.agent.knowledge;

/**
 * Skill Bundle 中的 H2 权威资源，不是宿主机路径。
 *
 * @param path Bundle 内相对资源名
 * @param mediaType 媒体类型
 * @param content UTF-8 资源正文
 * @param executable 是否为需要单独审批的脚本
 */
public record SkillResource(String path, String mediaType, String content, boolean executable) {
    /** 拒绝绝对路径、反斜线和路径穿越；脚本声明不授予任何执行权限。 */
    public SkillResource {
        if (path == null
                || path.isBlank()
                || path.length() > 240
                || path.startsWith("/")
                || path.contains("\\")
                || path.contains(":")
                || path.indexOf('\0') >= 0
                || java.util.Arrays.stream(path.split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("invalid Skill resource path");
        }
        mediaType = com.javaclaw.core.api.ThreadId.required(mediaType, "mediaType");
        content = java.util.Objects.requireNonNull(content, "content");
        if (content.length() > 262_144) {
            throw new IllegalArgumentException("Skill resource exceeds 256 Ki characters");
        }
    }
}
