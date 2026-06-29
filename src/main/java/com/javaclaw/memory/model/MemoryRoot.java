package com.javaclaw.memory.model;

import org.eclipse.store.gigamap.types.GigaMap;

import java.util.HashMap;
import java.util.Map;

/**
 * 记忆根对象图 —— 一个工作区(或全局库)的全部记忆形态的单一持久化根。
 *
 * <p>由 {@code MemoryStore} 作为 EclipseStore 根对象管理。各 {@link GigaMap} 各自挂 JVector 向量索引。
 * 字段内联初始化仅用于首次创建;重开时 EclipseStore 反序列化覆盖这些引用。</p>
 *
 * @author JavaClaw
 */
public class MemoryRoot {

    /** 语义记忆：事实 */
    public GigaMap<Fact> facts = GigaMap.New();

    /** 情景记忆：对话轮/事件 */
    public GigaMap<Episode> episodes = GigaMap.New();

    /** 知识库分块 */
    public GigaMap<KnowledgeChunk> knowledge = GigaMap.New();

    /** 记忆图实体节点 */
    public GigaMap<EntityNode> entities = GigaMap.New();

    /** 变更日志(替代备份) */
    public GigaMap<ChangeLogEntry> changeLog = GigaMap.New();

    /** 工作记忆检查点(会话/智能体 key → 快照) */
    public Map<String, AgentCheckpoint> working = new HashMap<>();

    /** 人格(可空,未设置时由上层注入默认) */
    public Persona persona;

    /** 统计 */
    public MemoryStats stats = new MemoryStats();

    public MemoryRoot() {}
}
