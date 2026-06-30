package com.javaclaw.memory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 人格 —— 人工撰写的角色 / 行为约定 / 偏好（替代旧 AGENTS.md 文件）。
 *
 * <p>不再是磁盘文件,改为对象图实体,经 MemoryCenterView 查看/编辑(可导出 markdown 备份)。
 * 每轮对话整段注入系统提示词(身份级,不参与 Top-K 检索)。</p>
 *
 * <p><b>结构化（P2）</b>：在保留 {@link #content}（实际注入文本）的同时，新增结构化字段
 * {@link #identity}/{@link #tone}/{@link #preferences}/{@link #taboos}。当 {@link #structured}
 * 为真时，{@code content} 由这些字段组装而成（见 {@code MemoryService.assemblePersona}）；
 * 旧的纯正文人格 {@code structured=false}，仍按原样注入，向后兼容。</p>
 *
 * @author JavaClaw
 */
public class Persona {

    /** 人格正文（markdown），每轮注入系统提示词（结构化时为组装结果） */
    public String content;

    public long updatedAt;

    // ==================== 结构化字段（可空，EclipseStore 反射持久化向后兼容） ====================

    /** 是否以结构化字段为真相（true 时 content 由下列字段组装） */
    public boolean structured;

    /** 身份（一句话角色定义，支持多行 markdown） */
    public String identity;

    /** 语气（简洁直接 / 耐心细致 / 活泼鼓励） */
    public String tone;

    /** 偏好清单 */
    public List<String> preferences = new ArrayList<>();

    /** 禁忌清单 */
    public List<String> taboos = new ArrayList<>();

    public Persona() {}

    public Persona(String content) {
        this.content = content;
        this.updatedAt = System.currentTimeMillis();
    }
}
