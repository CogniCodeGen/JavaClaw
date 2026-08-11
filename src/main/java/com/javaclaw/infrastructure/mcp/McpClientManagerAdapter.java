package com.javaclaw.infrastructure.mcp;

import com.javaclaw.application.mcp.McpConfigurationPort;
import com.javaclaw.application.mcp.McpManagementApplicationService.State;
import com.javaclaw.application.mcp.McpManagementApplicationService.Tool;
import com.javaclaw.application.mcp.McpRuntimePort;
import com.javaclaw.mcp.McpClient;
import com.javaclaw.mcp.McpClientManager;

import java.util.List;
import java.util.Objects;

/** 将 MCP 客户端生命周期适配到 Application 运行时端口。 */
public final class McpClientManagerAdapter implements McpRuntimePort {

    private final McpClientManager manager;

    public McpClientManagerAdapter(McpClientManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public Status status(String name) {
        McpClientManager.ServerStatus status = manager.getServerStatus(name);
        McpClient client = manager.getClient(name);
        List<Tool> tools = client == null ? List.of() : client.getTools().stream()
                .map(tool -> new Tool(tool.getName(), tool.getDescription())).toList();
        return new Status(State.valueOf(status.state().name()), tools,
                status.startupError(), status.startedAtMs());
    }

    @Override
    public boolean start(McpConfigurationPort.Entry configuration) {
        return manager.startServer(McpConfigManagerAdapter.toConfig(configuration));
    }

    @Override
    public boolean restart(McpConfigurationPort.Entry configuration) {
        return manager.restartServer(McpConfigManagerAdapter.toConfig(configuration));
    }

    @Override
    public void stop(String name) {
        manager.stopServer(name);
    }

    @Override
    public Test test(McpConfigurationPort.Entry configuration) {
        McpClientManager.TestResult result = manager.testConnection(
                McpConfigManagerAdapter.toConfig(configuration));
        return new Test(result.success(), result.tools().stream()
                .map(tool -> new Tool(tool.getName(), tool.getDescription())).toList(),
                result.elapsedMs(), result.errorMessage(), result.serverName(), result.serverVersion());
    }

    @Override
    public List<String> stderr(String name) {
        return manager.getStderrTail(name);
    }

    @Override
    public AutoCloseable observe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        manager.addStateListener(listener);
        return () -> manager.removeStateListener(listener);
    }
}
