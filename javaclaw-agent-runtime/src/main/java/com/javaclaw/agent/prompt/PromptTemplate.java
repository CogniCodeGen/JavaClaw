package com.javaclaw.agent.prompt;

/**
 * 发行物内置提示词，不从网络或数据库替换正文。
 *
 * @param id 非空稳定模板标识
 * @param version 正数版本，正文变化必须提升版本并审阅 manifest
 * @param content 非空 UTF-8 中文正文，不含用户资料
 * @param sha256 原文 SHA-256，必须匹配正文
 */
public record PromptTemplate(String id, int version, String content, String sha256) {
    /** 校验内容地址，防止模板资源与随包发布的 manifest 漂移。 */
    public PromptTemplate {
        if (id == null
                || !id.matches("[a-z_]+")
                || version < 1
                || content == null
                || content.isBlank()
                || !PromptHashes.sha256(content).equals(sha256)) {
            throw new IllegalArgumentException("invalid prompt template manifest");
        }
    }
}
