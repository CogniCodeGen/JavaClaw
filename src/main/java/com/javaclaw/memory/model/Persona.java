package com.javaclaw.memory.model;

/**
 * 人格 —— 人工撰写的角色 / 行为约定 / 偏好（替代旧 AGENTS.md 文件）。
 *
 * <p>不再是磁盘文件,改为对象图实体,经 MemoryCenterView 查看/编辑(可导出 markdown 备份)。
 * 每轮对话整段注入系统提示词(身份级,不参与 Top-K 检索)。</p>
 *
 * @author JavaClaw
 */
public class Persona {

    /** 人格正文（markdown），每轮注入系统提示词 */
    public String content;

    public long updatedAt;

    public Persona() {}

    public Persona(String content) {
        this.content = content;
        this.updatedAt = System.currentTimeMillis();
    }
}
