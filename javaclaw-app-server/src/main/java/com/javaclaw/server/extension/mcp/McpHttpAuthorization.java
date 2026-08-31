package com.javaclaw.server.extension.mcp;

import java.util.Map;

/** Resolves short-lived authorization headers without exposing credential values to callers. */
@FunctionalInterface
public interface McpHttpAuthorization {
    McpHttpAuthorization NONE = Map::of;

    /**
     * 仅为本次受控 HTTP 请求解析认证头；调用方不得缓存到公开配置或写入日志。
     *
     * @throws Exception 凭据不存在、刷新失败或 issuer/resource 绑定不匹配
     */
    Map<String, String> headers() throws Exception;
}
