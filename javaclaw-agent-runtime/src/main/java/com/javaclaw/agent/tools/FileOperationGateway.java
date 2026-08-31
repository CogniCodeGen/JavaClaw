package com.javaclaw.agent.tools;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 文件内容操作的受监督 Worker 边界；服务端主 JVM 不使用模型参数直接读写文件。 */
@FunctionalInterface
public interface FileOperationGateway {
    /** 在固定第一方入口执行请求；最终策略、取消和结果仍受当前 Turn 治理。 */
    ToolExecutionResult execute(FileRequest request, ToolExecutionContext context, SandboxPolicy policy)
            throws Exception;

    /**
     * 只包含工作区相对路径和有界内容的请求。
     *
     * @param operation READ、LIST、WRITE 或 REPLACE，由已注册工具决定
     * @param path 工作区相对路径；不允许绝对路径或父目录跳转
     * @param content WRITE 正文或 REPLACE 的新片段
     * @param expectedSha256 写入前摘要；创建新文件必须为 MISSING
     * @param oldText REPLACE 时必须在原文中唯一出现的片段
     * @param startLine 从 1 开始的读取行号
     * @param lineCount 本次读取行数，最多 500
     */
    record FileRequest(
            String operation,
            String path,
            String content,
            String expectedSha256,
            String oldText,
            int startLine,
            int lineCount) {}
}
