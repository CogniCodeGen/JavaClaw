package com.javaclaw.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能包 —— 一组相关技能 + 可选附加指令的组合（借鉴 hermes-agent bundles）。
 *
 * <p>语义：
 * <ul>
 *   <li>一次性加载：路由命中包名时，包内全部技能正文一起注入</li>
 *   <li>包优先：包内技能与单技能同名冲突时以包定义为准</li>
 *   <li>缺失跳过：包内某技能不存在时记日志跳过，不中断</li>
 *   <li>附加指令：{@code extraInstructions} 拼在包技能正文之后，预设使用方式</li>
 * </ul>
 *
 * <p>持久化：全局 {@code skills/bundles.json}（与技能目录同级，跨工作区复用）。
 * 公开字段 + 无参构造，Jackson 友好。</p>
 *
 * @author JavaClaw
 */
public class SkillBundle {

    /** 包名（路由与 UI 标识） */
    public String name;

    /** 包描述：一句话说明何时该用这个包 */
    public String description;

    /** 包内技能名列表（按 Skill.name 引用） */
    public List<String> skills = new ArrayList<>();

    /** 附加指令：加载包时拼在技能正文之后（可为空） */
    public String extraInstructions;

    /** 是否启用 */
    public boolean enabled = true;

    public SkillBundle() {
    }

    public SkillBundle(String name, String description, List<String> skills,
                       String extraInstructions, boolean enabled) {
        this.name = name;
        this.description = description;
        this.skills = skills != null ? skills : new ArrayList<>();
        this.extraInstructions = extraInstructions;
        this.enabled = enabled;
    }
}
