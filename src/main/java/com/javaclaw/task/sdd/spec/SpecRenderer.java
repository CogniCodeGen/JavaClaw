package com.javaclaw.task.sdd.spec;

import java.util.List;

/**
 * 类型化模型 → markdown 渲染器（{@link SpecParser} 的逆向）。
 *
 * <p>渲染格式与 {@code SKILL.md} 约定一致，且与 {@link SpecParser} 严格对称以保证
 * 写出→读回 round-trip 稳定。验收谓词以 {@code [type] predicate} 形式内联，便于解析复原。</p>
 *
 * @author JavaClaw
 */
public final class SpecRenderer {

    private SpecRenderer() {}

    /** proposal.md：为什么 / 改什么 / 不改什么。 */
    public static String renderProposal(String title, Proposal p) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 提案：").append(nz(title)).append("\n\n");
        sb.append("## 为什么\n\n").append(p == null ? "" : nz(p.why())).append("\n\n");
        sb.append("## 改什么\n\n").append(p == null ? "" : nz(p.whatChanges())).append("\n\n");
        sb.append("## 不改什么\n\n")
                .append(p == null || p.outOfScope() == null || p.outOfScope().isBlank()
                        ? "_（无）_" : p.outOfScope())
                .append("\n");
        return sb.toString();
    }

    /** 单个能力的 spec.md：需求 + Given/When/Then 场景 + 判据。 */
    public static String renderCapabilitySpec(Capability cap) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 能力：").append(nz(cap.name())).append("\n\n");
        for (Requirement req : cap.requirements()) {
            sb.append("## 需求：").append(nz(req.title())).append("\n\n");
            for (Scenario sc : req.scenarios()) {
                sb.append("### 场景：").append(nz(sc.title())).append("\n");
                sb.append("- **Given** ").append(nz(sc.given())).append("\n");
                sb.append("- **When** ").append(nz(sc.when())).append("\n");
                sb.append("- **Then** ").append(nz(sc.then())).append("\n");
                sb.append("- **判据**：").append(renderCriterion(sc.criterion())).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** 验收谓词内联形式：{@code [type] predicate}。 */
    public static String renderCriterion(Criterion c) {
        if (c == null) return "[" + Criterion.FREEFORM + "] ";
        return "[" + c.normalizedType() + "] " + nz(c.predicate());
    }

    /** tasks.md：有序可勾选实现项。 */
    public static String renderTasks(List<TaskItem> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 实现清单\n\n");
        if (tasks == null || tasks.isEmpty()) {
            sb.append("_（尚未拆解）_\n");
            return sb.toString();
        }
        for (TaskItem t : tasks) {
            sb.append("- [").append(t.done() ? "x" : " ").append("] ")
                    .append(t.index()).append(". ").append(nz(t.action()));
            if (t.files() != null && !t.files().isEmpty()) {
                sb.append("（").append(String.join(", ", t.files())).append("）");
            }
            if (t.criterion() != null && !t.criterion().isBlank()) {
                sb.append(" — 判据：").append(t.criterion());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
