package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * SDD 各阶段智能体的结构化输出 schema（AgentScope 据此生成 JSON Schema 约束模型）。
 *
 * <p>这些是<b>线缆层 DTO</b>：仅用于承接模型结构化输出，随即被 {@link AgentScopeSddAgents}
 * 映射为 {@code task.sdd.spec} 的不可变领域记录。字段描述直接作为给模型的指令。</p>
 *
 * @author JavaClaw
 */
public final class SddDrafts {

    private SddDrafts() {}

    /** 阶段 1-2：提案。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProposalDraft {
        @JsonPropertyDescription("为什么做：动机、要解决的问题、不做会怎样。回到用户需求的本质，剥离表层措辞。")
        @JsonProperty(required = true)
        public String why;

        @JsonPropertyDescription("改什么：高层变更点——新增/修改/删除哪些能力或模块。不写实现细节，只说对外要变成什么样。")
        @JsonProperty(required = true)
        public String whatChanges;

        @JsonPropertyDescription("不改什么：明确划出范围外的内容，防止范围蔓延。无明显范围外时填空字符串。")
        public String outOfScope;
    }

    /** 阶段 3：规格（能力 → 需求 → 场景 + 验收谓词）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpecDraft {
        @JsonPropertyDescription("受本次变更影响的能力列表。能力名用简洁 kebab-case 或中文短名（将作为目录名）。")
        @JsonProperty(required = true)
        public List<CapabilityDraft> capabilities;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapabilityDraft {
        @JsonPropertyDescription("能力名（目录名），如 chess-rule、login-flow。")
        @JsonProperty(required = true)
        public String name;

        @JsonPropertyDescription("该能力下的需求列表。每条需求是一个可验证的对外行为单元。")
        @JsonProperty(required = true)
        public List<RequirementDraft> requirements;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequirementDraft {
        @JsonPropertyDescription("需求标题：描述【做什么/对外行为】，不写实现。")
        @JsonProperty(required = true)
        public String title;

        @JsonPropertyDescription("刻画该需求的场景列表（至少一个），用 Given/When/Then 描述可观测行为。")
        @JsonProperty(required = true)
        public List<ScenarioDraft> scenarios;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenarioDraft {
        @JsonPropertyDescription("场景标题。")
        @JsonProperty(required = true)
        public String title;

        @JsonPropertyDescription("Given：前置条件。")
        public String given;

        @JsonPropertyDescription("When：触发动作。")
        public String when;

        @JsonPropertyDescription("Then：期望可观测结果。")
        public String then;

        @JsonPropertyDescription("验收谓词类型，五选一并【优先选确定性的前三类】：" +
                "artifact_exists（产物文件存在，predicate=工作目录相对路径）；" +
                "command_exit_zero（命令成功退出，predicate=命令文本，如 'mvn -q compile'）；" +
                "output_contains（命令输出含关键词，predicate 形如 '命令 ||| 期望子串'）；" +
                "external_check（外部检查，如 URL 200）；freeform（难以结构化的描述性标准）。")
        @JsonProperty(required = true)
        public String criterionType;

        @JsonPropertyDescription("验收谓词内容：按 criterionType 的约定填写（路径 / 命令 / '命令 ||| 子串' / 描述）。")
        @JsonProperty(required = true)
        public String criterionPredicate;
    }

    /** 阶段 5：任务清单。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskPlanDraft {
        @JsonPropertyDescription("有序实现项列表。每项细粒度（约 2–5 分钟可完成）、相互尽量独立、按依赖排序。")
        @JsonProperty(required = true)
        public List<TaskItemDraft> tasks;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskItemDraft {
        @JsonPropertyDescription("动作描述：具体可执行，如『在 Board 类新增 move(from,to) 方法』。")
        @JsonProperty(required = true)
        public String action;

        @JsonPropertyDescription("本项涉及/产出的文件路径（工作目录相对路径），可填多个；无明确文件产出时填空列表。")
        public List<String> files;

        @JsonPropertyDescription("完成判据：一句可核验的判定点，如『方法签名与 spec 场景一致』『mvn compile 通过』。")
        @JsonProperty(required = true)
        public String criterion;
    }

    /** critic 对单个描述性场景的判定。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CriticVerdictDraft {
        @JsonPropertyDescription("该场景是否在工作目录的现实产物中成立。务必以实际核查（inspect_list/inspect_read）为据，" +
                "证据不足时判 false，绝不默认通过。")
        @JsonProperty(required = true)
        public boolean pass;

        @JsonPropertyDescription("判定理由：引用你核查到的具体证据（文件/内容/缺失），一两句话。")
        @JsonProperty(required = true)
        public String reason;
    }

    /** 验收补做：未通过场景 → 补做动作。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemediationDraft {
        @JsonPropertyDescription("针对未通过的验收场景，给出补做的实现项动作列表（保留已完成工作，只补缺口）。" +
                "若确实无法给出可行补做，填空列表。")
        @JsonProperty(required = true)
        public List<String> fixes;
    }
}
