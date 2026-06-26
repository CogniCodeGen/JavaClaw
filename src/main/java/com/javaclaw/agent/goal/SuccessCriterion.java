package com.javaclaw.agent.goal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 结构化成功准则 — 单条可验证的目标完成谓词。
 *
 * <p>由 {@link GoalManager} 在目标分解时一并产出，比纯自由文本的
 * {@code successCriteria} 更易于事后核验（GEPA 评估早退、质疑智能体逐条核对）。</p>
 *
 * <p>{@link #type} 取值约定（小写下划线，匹配 LLM 输出习惯）：</p>
 * <ul>
 *   <li>{@code artifact_exists} — 产物存在性，{@code predicate} 为路径或描述</li>
 *   <li>{@code command_exit_zero} — 命令成功退出，{@code predicate} 为命令文本</li>
 *   <li>{@code output_contains} — 输出包含关键词，{@code predicate} 为关键词或正则</li>
 *   <li>{@code external_check} — 外部检查（URL 200、邮件送达等），{@code predicate} 为可读描述</li>
 *   <li>{@code freeform} — 难以结构化的描述性标准，{@code predicate} 即为标准文本</li>
 * </ul>
 *
 * @author JavaClaw
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuccessCriterion {

    public String type;
    public String predicate;

    public SuccessCriterion() {
    }

    public SuccessCriterion(String type, String predicate) {
        this.type = type;
        this.predicate = predicate;
    }

    @Override
    public String toString() {
        return "[" + (type == null ? "freeform" : type) + "] " + (predicate == null ? "" : predicate);
    }
}
