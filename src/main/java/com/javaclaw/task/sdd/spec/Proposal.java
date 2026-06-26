package com.javaclaw.task.sdd.spec;

/**
 * 变更提案 —— 对应 {@code proposal.md}，回答"为什么做 + 改什么 + 不改什么"。
 *
 * <p>取代 v5 {@code ClarifyResult} 的意图/边界语义，是 OpenSpec change 的高层动机说明，
 * 也是第一道人机评审（确认 proposal）展示的内容。</p>
 *
 * @param why          动机：要解决的问题、不做会怎样
 * @param whatChanges  高层变更点（新增/修改/删除哪些能力）
 * @param outOfScope   明确划出的范围外内容，防止范围蔓延（可空）
 * @author JavaClaw
 */
public record Proposal(String why, String whatChanges, String outOfScope) {
}
