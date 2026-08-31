package com.javaclaw.server.extension;

/**
 * Plugin 4.0 包内 Skill 声明，不提供 Java 类入口。
 *
 * @param id 插件包内的 Skill 标识
 * @param path 安全包内相对路径，不允许绝对路径或父目录穿越
 */
public record PluginSkillContribution(String id, String path) {
    /** 校验 Skill id 和包内相对路径；不读取文件或执行代码。 */
    public PluginSkillContribution {
        id = PluginValidation.id(id, "skill id");
        path = PluginValidation.relativePath(path, "skill path");
    }
}
