package com.javaclaw.skill;

/**
 * 技能来源
 *
 * <p>标识技能由谁创建，用于 UI 徽标展示与 user-modified 保护策略：
 * <ul>
 *   <li>{@link #BUILTIN} — 项目内置技能（随仓库分发）</li>
 *   <li>{@link #USER} — 用户手动创建/导入的技能</li>
 *   <li>{@link #AGENT} — 智能体通过 skill_manage 工具或自学习闭环沉淀的技能（程序性记忆）</li>
 * </ul>
 *
 * @author JavaClaw
 */
public enum SkillSource {

    /** 项目内置 */
    BUILTIN("builtin", "内置"),

    /** 用户创建 */
    USER("user", "用户"),

    /** 智能体自学习创建 */
    AGENT("agent", "自学习");

    /** YAML frontmatter 中的取值 */
    private final String key;

    /** UI 展示名 */
    private final String displayName;

    SkillSource(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 从 frontmatter 字符串解析来源，未知值回落为 USER（向后兼容旧技能）
     */
    public static SkillSource fromKey(String key) {
        if (key == null || key.isBlank()) {
            return USER;
        }
        for (SkillSource s : values()) {
            if (s.key.equalsIgnoreCase(key.strip())) {
                return s;
            }
        }
        return USER;
    }
}
