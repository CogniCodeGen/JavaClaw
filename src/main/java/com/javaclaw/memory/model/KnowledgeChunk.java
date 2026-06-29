package com.javaclaw.memory.model;

/**
 * 知识库分块 —— 导入文档切分后的一段（替代 KnowledgeExpert 旧 {@code ChunkData} + InMemoryStore）。
 *
 * <p>与事实/情景共用同一 EclipseStore 引擎与 JVector 索引体系（P4 折叠知识库进统一基座）。
 * {@link #docName} 直接随实体持久化 —— 彻底消除旧 InMemoryStore 不复制 vectorName 的绕路。</p>
 *
 * @author JavaClaw
 */
public class KnowledgeChunk {

    public String id;
    public long entityId;

    /** 来源文档名（文件名或导入标题） */
    public String docName;

    /** 作用域："GLOBAL" / "WORKSPACE" */
    public String scope;

    /** 分块文本 */
    public String content;

    /** 预计算向量 */
    public float[] embedding;

    public int chunkIndex;
    public String importTime;

    public KnowledgeChunk() {}

    public KnowledgeChunk(String docName, String scope, String content, float[] embedding) {
        this.docName = docName;
        this.scope = scope;
        this.content = content;
        this.embedding = embedding;
    }
}
