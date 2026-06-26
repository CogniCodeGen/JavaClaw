package com.javaclaw.plugin.api;

/**
 * 插件动态提供的一个技能 —— 通过 {@link SkillProvider} 声明，由宿主<b>动态注册</b>进技能系统的
 * 渐进式暴露（L0 目录 / L1 详情），<b>不落盘</b>，插件卸载时同步移除。
 *
 * @param name        技能名（并入 L0 目录、可经 skill_read 拉取全文）
 * @param description 技能描述（L0 目录展示）
 * @param content     SKILL.md 正文（L1 全文，内存持有）
 * @author JavaClaw
 */
public record PluginSkill(String name, String description, String content) {
}
