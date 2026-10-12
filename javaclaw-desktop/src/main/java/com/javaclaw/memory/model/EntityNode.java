package com.javaclaw.memory.model;

/**
 * 记忆图节点 —— 实体 / 概念（如某项目、某工具、某人）。
 *
 * <p>由事实引用（{@link Fact#about}）形成横向关联,支撑"图扩展检索"(命中事实 → 拉关联实体 →
 * 再拉该实体上的其它事实)。这是对象图相对旧 markdown 树的核心增量。P5 启用图扩展逻辑。</p>
 *
 * @author JavaClaw
 */
public class EntityNode {

    public String id;
    public long entityId;

    /** 实体名（规范化后用于去重） */
    public String name;

    /** 实体类型（project / tool / person / topic …，自由文本） */
    public String type;

    /** 可选向量（按实体名嵌入，用于实体级语义检索；可空） */
    public float[] embedding;

    public EntityNode() {}

    public EntityNode(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
