package com.javaclaw.api;

import java.util.Objects;

/**
 * 可保存为 UTF-8 文件的 Role 导出结果，不包含任何凭据。
 *
 * @param filename 建议文件名，仅包含稳定 Role 标识和 .agent.toml 后缀
 * @param content 完整 TOML 正文
 * @param contentDigest 正文 SHA-256
 * @param format 导出交换模式
 */
public record AgentRoleFileExport(String filename, String content, String contentDigest, AgentRoleFileFormat format) {
    /** 校验安全文件名和内容摘要。 */
    public AgentRoleFileExport {
        filename = Preconditions.text(filename, "filename");
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}\\.agent\\.toml")) {
            throw new IllegalArgumentException("filename must be a Role agent TOML basename");
        }
        content = Objects.requireNonNull(content, "content");
        contentDigest = Preconditions.digest(contentDigest, "contentDigest");
        Objects.requireNonNull(format, "format");
    }
}
