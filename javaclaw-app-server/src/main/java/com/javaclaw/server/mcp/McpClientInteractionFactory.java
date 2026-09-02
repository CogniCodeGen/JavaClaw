package com.javaclaw.server.mcp;

import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;

/** 将 MCP 反向交互绑定到正在执行的精确 Turn。 */
@FunctionalInterface
public interface McpClientInteractionFactory {
    /**
     * 创建只属于一次工具调用的交互端口。
     *
     * @param turnId 当前 Turn
     * @param workspaceId 当前 Workspace
     * @param endpoint 执行前读取的 Endpoint revision
     * @return 受治理交互端口
     */
    McpClientInteractionPort bind(TurnId turnId, WorkspaceId workspaceId, McpEndpoint endpoint);
}
