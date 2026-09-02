package com.javaclaw.server.mcp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;

/** 在 Turn Harness 装配完成后一次性绑定的 MCP 交互入口。 */
public final class DeferredMcpClientInteractionFactory implements McpClientInteractionFactory {
    private final AtomicReference<McpClientInteractionFactory> delegate = new AtomicReference<>();

    /** @param factory 真实 Turn 交互工厂 */
    public void bind(McpClientInteractionFactory factory) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(factory, "factory"))) {
            throw new IllegalStateException("MCP interaction factory is already bound");
        }
    }

    @Override
    public McpClientInteractionPort bind(TurnId turnId, WorkspaceId workspaceId, McpEndpoint endpoint) {
        McpClientInteractionFactory current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("MCP interaction factory is not bound");
        }
        return current.bind(turnId, workspaceId, endpoint);
    }
}
