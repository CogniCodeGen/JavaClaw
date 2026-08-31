package com.javaclaw.server.extension.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Resolves one MRTR input request inside the authority and budget of the current Turn. */
@FunctionalInterface
public interface McpInputResolver {
    McpInputResolver REJECT_ALL = (requestId, request, invocation) -> {
        throw new IllegalStateException("MCP server requested unsupported client input: "
                + request.path("method").asText("unknown"));
    };

    /** 处理一次 MRTR input_required 请求，绑定当前 Turn 的用户输入、预算型 Sampling 或受限 Roots；不得递归开放工具。 */
    JsonNode resolve(String requestId, JsonNode request, McpInvocation invocation) throws Exception;
}
