package com.javaclaw.extension.spi;

import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;

/**
 * MCP 远端传输端口。
 *
 * <p>HTTPS 实现必须经 App Server Network Broker；stdio 实现必须经签名 Bundle Sandbox。实现只接收 {@code CredentialRef}，Secret 解析与 header
 * 注入必须在更低层受限边界完成。
 */
public interface McpRemotePort {
    /**
     * 以唯一固定协议执行 initialize。
     *
     * @param endpoint 精确端点版本
     * @param requiredProtocol JavaClaw 固定版本
     * @param cancellation 取消信号
     * @return 远端确认结果
     * @throws Exception 传输、认证或协议失败
     */
    McpRemoteSession initialize(McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation)
            throws Exception;

    /**
     * 读取一页 Catalog。
     *
     * @param endpoint 精确端点版本
     * @param cursor 可选远端 cursor
     * @param cancellation 取消信号
     * @return 规范化目录页
     * @throws Exception 传输或内容校验失败
     */
    McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception;

    /**
     * 读取一页外部 Resource 描述。
     *
     * @param endpoint 精确端点版本
     * @param cursor 可选远端 cursor
     * @param cancellation 取消信号
     * @return 强类型外部 Resource 页
     * @throws Exception 传输、协议或内容校验失败
     */
    default McpResourcePage resources(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        throw new UnsupportedOperationException("MCP remote transport does not expose resources");
    }

    /**
     * 显式读取一个外部 Resource。
     *
     * @param endpoint 精确端点版本
     * @param uri 远端声明的绝对 URI
     * @param cancellation 取消信号
     * @return 强类型外部内容
     * @throws Exception 传输、协议或内容校验失败
     */
    default McpResourceReadResult readResource(McpEndpoint endpoint, String uri, CancellationToken cancellation)
            throws Exception {
        throw new UnsupportedOperationException("MCP remote transport does not expose resources");
    }

    /**
     * 读取一页外部 Prompt 描述。
     *
     * @param endpoint 精确端点版本
     * @param cursor 可选远端 cursor
     * @param cancellation 取消信号
     * @return 强类型外部 Prompt 页
     * @throws Exception 传输、协议或内容校验失败
     */
    default McpPromptPage prompts(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        throw new UnsupportedOperationException("MCP remote transport does not expose prompts");
    }

    /**
     * 显式展开一个外部 Prompt 模板。
     *
     * @param endpoint 精确端点版本
     * @param name Prompt 名称
     * @param arguments 用户显式提供的字符串参数
     * @param cancellation 取消信号
     * @return 强类型外部消息
     * @throws Exception 传输、协议或内容校验失败
     */
    default McpPromptResult getPrompt(
            McpEndpoint endpoint, String name, Map<String, String> arguments, CancellationToken cancellation)
            throws Exception {
        throw new UnsupportedOperationException("MCP remote transport does not expose prompts");
    }

    /**
     * 执行已治理 Tool。
     *
     * @param endpoint 执行前重新读取的端点
     * @param request 冻结身份和参数
     * @param interactions 受限 elicitation/sampling 回调
     * @param cancellation 取消信号
     * @return Tool 结果
     * @throws Exception 远端或交互失败
     */
    McpInvocationResult invoke(
            McpEndpoint endpoint,
            McpInvocationRequest request,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception;
}
