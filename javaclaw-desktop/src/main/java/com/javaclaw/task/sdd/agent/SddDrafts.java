package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * SDD 各阶段智能体的结构化输出 schema（Spring AI 据此生成 JSON Schema 约束模型）。
 *
 * <p>这些是<b>线缆层 DTO</b>：仅用于承接模型结构化输出，随即被 {@link FrameworkSddAgents}
 * 映射为 {@code task.sdd.spec} 的不可变领域记录。字段描述直接作为给模型的指令。</p>
 *
 * @author JavaClaw
 */
public final class SddDrafts {

    private SddDrafts() {}

    /** 阶段 1-2：提案。 */
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
    public static class SpecDraft {
        @JsonPropertyDescription("受本次变更影响的能力列表。能力名用简洁 kebab-case 或中文短名（将作为目录名）。")
        @JsonProperty(required = true)
        public List<CapabilityDraft> capabilities;
    }

    public static class CapabilityDraft {
        @JsonPropertyDescription("能力名（目录名），如 chess-rule、login-flow。")
        @JsonProperty(required = true)
        public String name;

        @JsonPropertyDescription("该能力下的需求列表。每条需求是一个可验证的对外行为单元。")
        @JsonProperty(required = true)
        public List<RequirementDraft> requirements;
    }

    public static class RequirementDraft {
        @JsonPropertyDescription("需求标题：描述【做什么/对外行为】，不写实现。")
        @JsonProperty(required = true)
        public String title;

        @JsonPropertyDescription("刻画该需求的场景列表（至少一个），用 Given/When/Then 描述可观测行为。")
        @JsonProperty(required = true)
        public List<ScenarioDraft> scenarios;
    }

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

        @JsonPropertyDescription("验收谓词类型，四选一并优先选择前两类。不得生成 output_contains；" +
                "artifact_exists（产物文件存在，predicate=工作目录相对路径）；" +
                "command_exit_zero（命令成功退出，predicate=命令文本，如 'mvn -q compile'）；" +
                "external_check（外部检查，如 URL 200）；freeform（难以结构化的描述性标准）。")
        @JsonProperty(required = true)
        public CriterionKind criterionType;

        @JsonPropertyDescription("验收谓词内容：按 criterionType 的约定填写（路径 / 命令 / 描述）。")
        @JsonProperty(required = true)
        public String criterionPredicate;
    }

    /** 阶段 5：任务清单。 */
    public static class TaskPlanDraft {
        @JsonPropertyDescription("有序实现项列表。每项细粒度（约 2–5 分钟可完成）、相互尽量独立、按依赖排序。")
        @JsonProperty(required = true)
        public List<TaskItemDraft> tasks;
    }

    /** Whether this change needs a separate technical design document. */
    public static class DesignDraft {
        @JsonPropertyDescription("是否存在需要单独说明的非平凡技术权衡。")
        @JsonProperty(required = true)
        public boolean required;

        @JsonPropertyDescription("required=true 时的 design.md Markdown 正文；否则填空字符串。")
        @JsonProperty(required = true)
        public String content;
    }

    public static class TaskItemDraft {
        @JsonPropertyDescription("任务类别：IMPLEMENTATION 表示代码、配置、测试或构建动作；PROCESS_META 表示方案展示、文档流程或评审等待。")
        @JsonProperty(required = true)
        public TaskKind kind;

        @JsonPropertyDescription("动作描述：具体可执行，如『在 Board 类新增 move(from,to) 方法』。")
        @JsonProperty(required = true)
        public String action;

        @JsonPropertyDescription("本项涉及/产出的文件路径（工作目录相对路径），可填多个；无明确文件产出时填空列表。")
        public List<String> files;

        @JsonPropertyDescription("完成判据：一句可核验的判定点，如『方法签名与 spec 场景一致』『mvn compile 通过』。")
        @JsonProperty(required = true)
        public String criterion;
    }

    public enum TaskKind { IMPLEMENTATION, PROCESS_META }

    public enum CriterionKind {
        ARTIFACT_EXISTS, COMMAND_EXIT_ZERO, EXTERNAL_CHECK, FREEFORM
    }

    public enum ExecutionDisposition { DONE, SPLIT }

    public static class ExecutionDispositionDraft {
        @JsonPropertyDescription("DONE 表示本实现项已执行并可进入独立验收；SPLIT 表示必须拆成更小实现项。")
        @JsonProperty(required = true)
        public ExecutionDisposition kind;

        @JsonPropertyDescription("SPLIT 时填写 2–5 个具体子项；DONE 时填空列表。")
        @JsonProperty(required = true)
        public List<String> subtasks;
    }

    /** critic 对单个描述性场景的判定。 */
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
    public static class RemediationDraft {
        @JsonPropertyDescription("针对未通过的验收场景，给出补做的实现项动作列表（保留已完成工作，只补缺口）。" +
                "若确实无法给出可行补做，填空列表。")
        @JsonProperty(required = true)
        public List<RemediationItemDraft> fixes;
    }

    public static class RemediationItemDraft {
        @JsonPropertyDescription("补做项类别：IMPLEMENTATION 为真实实现；PROCESS_META 为流程或评审动作。")
        @JsonProperty(required = true)
        public TaskKind kind;

        @JsonPropertyDescription("具体可执行的补做动作。")
        @JsonProperty(required = true)
        public String action;
    }
}
