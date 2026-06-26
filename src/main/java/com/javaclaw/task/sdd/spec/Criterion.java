package com.javaclaw.task.sdd.spec;

/**
 * 单条可验证的验收谓词 —— 一个场景（{@link Scenario}）的成功判据。
 *
 * <p>沿用项目既有 {@code com.javaclaw.agent.goal.SuccessCriterion} 的五类谓词约定，
 * 但落在 SDD 真相层内、与 GEPA 解耦。{@code type} 取小写下划线，匹配 LLM 输出习惯：</p>
 * <ul>
 *   <li>{@code artifact_exists} — 产物存在性，{@code predicate} 为工作目录相对/绝对路径</li>
 *   <li>{@code command_exit_zero} — 命令成功退出，{@code predicate} 为命令文本</li>
 *   <li>{@code output_contains} — 输出包含关键词，{@code predicate} 为关键词或正则</li>
 *   <li>{@code external_check} — 外部检查（URL 200、邮件送达等），{@code predicate} 为可读描述</li>
 *   <li>{@code freeform} — 难以结构化的描述性标准，{@code predicate} 即标准文本</li>
 * </ul>
 *
 * <p>前三类是<b>确定性谓词</b>（{@link #isDeterministic()}），可由代码直接核验；
 * 后两类需要 critic 智能体或外部探测判定。</p>
 *
 * @author JavaClaw
 */
public record Criterion(String type, String predicate) {

    public static final String ARTIFACT_EXISTS = "artifact_exists";
    public static final String COMMAND_EXIT_ZERO = "command_exit_zero";
    public static final String OUTPUT_CONTAINS = "output_contains";
    public static final String EXTERNAL_CHECK = "external_check";
    public static final String FREEFORM = "freeform";

    /** 归一化 type；null/空白按 freeform 处理。 */
    public String normalizedType() {
        return (type == null || type.isBlank()) ? FREEFORM : type.trim().toLowerCase();
    }

    /** 是否为代码可直接核验的确定性谓词（前三类）。 */
    public boolean isDeterministic() {
        String t = normalizedType();
        return ARTIFACT_EXISTS.equals(t) || COMMAND_EXIT_ZERO.equals(t) || OUTPUT_CONTAINS.equals(t);
    }

    /** freeform 兜底构造（仅有判据文本、无结构化类型时）。 */
    public static Criterion freeform(String predicate) {
        return new Criterion(FREEFORM, predicate);
    }
}
