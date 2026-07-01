package com.javaclaw.memory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义记忆 —— 蒸馏出的一条可长期记住的事实（替代旧 MEMORY.md 的 {@code - } 条目）。
 *
 * <p>作为 EclipseStore 对象图实体持久化；{@link #embedding} 供 JVector 向量索引检索。
 * 字段公开以契合 EclipseStore 反射持久化与向量器直接读取（与 KnowledgeExpert 旧 DTO 风格一致）。</p>
 *
 * @author JavaClaw
 */
public class Fact {

    /** 业务主键（UUID），与 GigaMap 的 long entityId 区分 */
    public String id;

    /** GigaMap 内部实体 id（add 后回填，用于 update/remove） */
    public long entityId;

    /** 所属主题（用户画像 / 偏好与工具栈 / 项目背景 …） */
    public String section;

    /** 事实本体（一句话陈述） */
    public String text;

    /** 预计算向量（由服务层用嵌入模型生成后写入） */
    public float[] embedding;

    public long createdAt;
    public long updatedAt;

    /** 命中归因计数（被检索注入的次数），反哺保留 / 淘汰 */
    public int hitCount;

    /** 用户是否手动改过 —— 保护位：蒸馏不得静默覆盖 */
    public boolean userEdited;

    /** 置顶 —— 钉住重要事实（UI 排序靠前；语义上等同强保护，不被淘汰） */
    public boolean pinned;

    /**
     * 待嵌入标记 —— 嵌入服务不可用时降级落在 {@code pendingFacts}（无向量、不可召回），
     * 服务恢复后重嵌入迁入正式 {@code facts} 索引。true 时增删改须路由到 pending 存储。
     */
    public boolean pending;

    /** 来源情景（对象引用 = 记忆图的一条边，可空） */
    public Episode source;

    /** 关联实体（记忆图的边，可空） */
    public List<EntityNode> about = new ArrayList<>();

    public Fact() {}

    public Fact(String section, String text, float[] embedding) {
        this.section = section;
        this.text = text;
        this.embedding = embedding;
    }
}
