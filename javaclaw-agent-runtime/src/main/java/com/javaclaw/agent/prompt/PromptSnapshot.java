package com.javaclaw.agent.prompt;

import java.util.List;

/**
 * 实际提示词的脱敏运行档案；只存引用和哈希，不把完整私有上下文写入日志。
 *
 * @param purpose 调用用途
 * @param templates 有序模板版本引用
 * @param profileId Profile 标识；隔离采样可为空字符串
 * @param profileRevision Profile 版本；临时调用可为零
 * @param personaSha256 人设原文摘要
 * @param toolCatalogSha256 本 Turn 工具描述符与 Schema 的有序摘要
 * @param contexts 实际采用的有序来源引用
 * @param compiledSha256 实际消息角色、顺序与正文的摘要
 */
public record PromptSnapshot(
        PromptPurpose purpose,
        List<TemplateRef> templates,
        String profileId,
        long profileRevision,
        String personaSha256,
        String toolCatalogSha256,
        List<ContextRef> contexts,
        String compiledSha256) {
    /** 固定引用顺序，避免模型收到的内容与审计快照漂移。 */
    public PromptSnapshot {
        templates = List.copyOf(templates);
        contexts = List.copyOf(contexts);
    }

    /**
     * 内置模板引用。
     *
     * @param id 模板标识
     * @param version 正数模板版本
     * @param sha256 模板原文哈希
     */
    public record TemplateRef(String id, int version, String sha256) {}

    /**
     * 参考资料或 AGENTS.md 指令的内容地址。
     *
     * @param source 来源类别
     * @param id 来源标识
     * @param revision 来源版本，临时资料可为零
     * @param sha256 原文哈希
     * @param bytes 实际注入正文的 UTF-8 字节数
     * @param truncated 是否因项目指令预算而截断
     */
    public record ContextRef(String source, String id, long revision, String sha256, long bytes, boolean truncated) {
        /** 创建不携带字节扩展元数据的兼容引用。 */
        public ContextRef(String source, String id, long revision, String sha256) {
            this(source, id, revision, sha256, 0, false);
        }
    }
}
