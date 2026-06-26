package com.javaclaw.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能变更请求 —— skill_manage 工具与自学习闭环的统一变更载体。
 *
 * <p>承载一次 create / patch / edit / delete / write_file / remove_file 动作的全部参数：
 * 直接落盘（auto 模式）时由 {@link SkillManager} 消费；走提案（suggest 模式或
 * user-modified 保护降级）时包进 SkillProposal 排队待审，采纳后再消费。
 * 公开字段 + 无参构造，Jackson 序列化友好（随提案持久化到 skill-proposals.json）。</p>
 *
 * @author JavaClaw
 */
public class SkillChangeRequest {

    /** 动作：create / patch / edit / delete / write_file / remove_file */
    public String action;

    /** 目标技能名（create 时为新技能名） */
    public String skillName;

    /** 技能描述（create 用） */
    public String description;

    /** 分类（create 用） */
    public String category;

    /** 标签（create 用） */
    public List<String> tags = new ArrayList<>();

    /** 完整正文（create / edit 用） */
    public String content;

    /** 被替换的原文片段（patch 用，须唯一） */
    public String oldString;

    /** 替换后的新内容（patch 用） */
    public String newString;

    /** 相对技能根目录的文件路径（write_file / remove_file 用） */
    public String relPath;

    /** 支持文件内容（write_file 用） */
    public String fileContent;

    /** 变更理由（给用户审阅看） */
    public String reason;

    /** 是否覆盖用户修改过的技能（审阅 UI 高亮警示） */
    public boolean userModifiedWarning;

    public SkillChangeRequest() {
    }

    /**
     * 把变更请求应用到 {@link SkillManager}（真正落盘）。
     *
     * @return null 表示成功；否则返回失败原因（中文）
     */
    public String apply() {
        SkillManager mgr = SkillManager.getInstance();
        return switch (action == null ? "" : action) {
            case "create" -> {
                Skill created = mgr.createAgentSkill(skillName,
                        description != null ? description : "",
                        content != null ? content : "",
                        category != null ? category : "",
                        tags != null ? tags : new ArrayList<>());
                yield created == null ? "技能「" + skillName + "」已存在，创建失败" : null;
            }
            case "patch" -> mgr.applyPatch(skillName, oldString, newString);
            case "edit" -> mgr.applyEdit(skillName, content);
            case "delete" -> {
                Skill target = mgr.getSkillByName(skillName);
                if (target == null) {
                    yield "未找到名为「" + skillName + "」的技能";
                }
                mgr.deleteSkill(target.getId());
                yield null;
            }
            case "write_file" -> mgr.writeSupportFile(skillName, relPath, fileContent);
            case "remove_file" -> mgr.removeSupportFile(skillName, relPath);
            default -> "未知动作：" + action;
        };
    }

    /**
     * 变更指纹（skillName + action + 内容 hash）：同指纹去重与被拒冷却的依据
     */
    public String fingerprint() {
        String payload = String.join("|",
                nz(content), nz(oldString), nz(newString), nz(relPath), nz(fileContent));
        return nz(skillName) + "#" + nz(action) + "#" + Integer.toHexString(payload.hashCode());
    }

    /** 给审阅 UI 的变更内容预览 */
    public String previewText() {
        return switch (action == null ? "" : action) {
            case "create" -> "描述：" + nz(description) + "\n分类：" + nz(category)
                    + "\n标签：" + (tags == null ? "" : String.join("、", tags)) + "\n\n" + nz(content);
            case "patch" -> "【原文】\n" + nz(oldString) + "\n\n【替换为】\n" + nz(newString);
            case "edit" -> nz(content);
            case "delete" -> "（删除整个技能目录，含版本历史）";
            case "write_file" -> "文件：" + nz(relPath) + "\n\n" + nz(fileContent);
            case "remove_file" -> "删除文件：" + nz(relPath);
            default -> "";
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
