package com.javaclaw.skill.curation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 技能蒸馏结构化输出 schema（AgentScope 据此生成 JSON Schema 约束模型）。
 *
 * <p>线缆层 DTO：SkillCurator 用轻量模型从一轮对话/一个 SDD 任务的执行轨迹中
 * 蒸馏「是否值得沉淀为技能、create 新技能还是 patch 既有技能」的判断。
 * 字段描述直接作为给模型的指令（参照 SddDrafts 风格）。</p>
 *
 * @author JavaClaw
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillCurationDraft {

    @JsonPropertyDescription("本次经历是否值得沉淀为技能。仅当满足以下之一才为 true：" +
            "完成了非平凡的多步骤工作流并验证成功；踩坑后找到了可复用的解决路径；" +
            "用户纠正了做法且新做法有持续意义。普通问答、一次性查询、无新经验时必须为 false。")
    @JsonProperty(required = true)
    public boolean worthLearning;

    @JsonPropertyDescription("动作：create=创建新技能（现有技能均不覆盖该经验时）；" +
            "patch=修补既有技能（经验是对某现有技能的补充/纠正时，优先选这个）；none=不沉淀。")
    @JsonProperty(required = true)
    public String action;

    @JsonPropertyDescription("技能名称：create 时为新技能名（简短、能表达用途）；patch 时必须是现有技能目录中列出的名称。")
    @JsonProperty(required = true)
    public String skillName;

    @JsonPropertyDescription("沉淀理由（给用户审阅看的一句话：从这次经历中学到了什么、为什么值得记住）。")
    @JsonProperty(required = true)
    public String reason;

    @JsonPropertyDescription("技能描述：一句话说明何时该用这个技能（create 用，patch 时填空字符串）。")
    public String description;

    @JsonPropertyDescription("分类（如：编码/浏览器/系统/办公；create 用，可为空字符串）。")
    public String category;

    @JsonPropertyDescription("标签列表（create 用，可为空数组）。")
    public List<String> tags;

    @JsonPropertyDescription("技能完整正文（Markdown，create 用，patch 时填空字符串）：" +
            "按「## 适用场景 → ## 操作步骤 → ## 注意事项 → ## 验证方法」组织，写可执行的具体步骤而非空泛原则。")
    public String content;

    @JsonPropertyDescription("patch 用：要被替换的原文片段（须与目标技能正文完全一致且唯一；create 时填空字符串）。")
    public String oldString;

    @JsonPropertyDescription("patch 用：替换后的新内容（create 时填空字符串）。")
    public String newString;
}
