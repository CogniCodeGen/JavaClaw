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
 *   <li>{@code output_contains} — 输出包含关键词，{@code predicate} 为字面关键词（子串匹配，不按正则解释）</li>
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

    /**
     * 归一化类型码：{@code null}/空白按 {@code freeform} 兜底，否则去首尾空白并<b>转小写</b>
     * （类型码由 LLM 产出，约定小写下划线但常见大小写变体如 {@code Command_exit_zero}——
     * 不归一会让本可确定性核验的准则落进未知类型分支）。
     *
     * <p>供核验器（判该走命令/文件/关键词/验收员哪条路）、重验调度器（缓存失效策略按类型分档）
     * 与循环装配层（统计无法本地核验的准则数）共用同一份归一化——各写一份的话，改动
     * trim/大小写规则时只改一处会让各方对同一准则分类不一致。</p>
     */
    public String normalizedType() {
        return (type == null || type.isBlank()) ? "freeform"
                : type.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "[" + (type == null ? "freeform" : type) + "] " + (predicate == null ? "" : predicate);
    }
}
