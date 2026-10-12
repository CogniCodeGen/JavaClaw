package com.javaclaw.memory.model;

/**
 * 变更日志条目 —— 记忆任一变更的 append-only 审计轨（**取代备份策略**）。
 *
 * <p>每次事实/情景/知识/人格的增删改都追加一条,记录 时间/操作/类型/目标/发起方/摘要。
 * 提供可追溯性与按需重放能力,替代"覆写前快照文件"的旧备份思路。</p>
 *
 * @author JavaClaw
 */
public class ChangeLogEntry {

    public long timestamp;

    /** 操作：ADD / UPDATE / REMOVE / MERGE / PERSONA_EDIT … */
    public String op;

    /** 目标类型：Fact / Episode / KnowledgeChunk / Persona … */
    public String type;

    /** 目标业务 id（可空，如 Persona 整体编辑） */
    public String targetId;

    /** 发起方：distiller / user / agent / system */
    public String actor;

    /** 摘要（变更要点，便于人读审计） */
    public String detail;

    public ChangeLogEntry() {}

    public ChangeLogEntry(long timestamp, String op, String type, String targetId, String actor, String detail) {
        this.timestamp = timestamp;
        this.op = op;
        this.type = type;
        this.targetId = targetId;
        this.actor = actor;
        this.detail = detail;
    }
}
