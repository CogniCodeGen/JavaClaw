package com.javaclaw.application.mcp;

import java.util.List;

/** MCP 连接运行时端口；实现负责进程/网络资源的生命周期。 */
public interface McpRuntimePort {

    Status status(String name);

    boolean start(McpConfigurationPort.Entry configuration);

    boolean restart(McpConfigurationPort.Entry configuration);

    void stop(String name);

    Test test(McpConfigurationPort.Entry configuration);

    List<String> stderr(String name);

    AutoCloseable observe(Runnable listener);

    record Status(
            McpManagementApplicationService.State state,
            List<McpManagementApplicationService.Tool> tools,
            String startupError,
            long startedAtMs) {
        public Status {
            state = state == null ? McpManagementApplicationService.State.STOPPED : state;
            tools = List.copyOf(tools == null ? List.of() : tools);
            startupError = startupError == null ? "" : startupError;
        }
    }

    record Test(
            boolean success,
            List<McpManagementApplicationService.Tool> tools,
            long elapsedMs,
            String errorMessage,
            String serverName,
            String serverVersion) {
        public Test {
            tools = List.copyOf(tools == null ? List.of() : tools);
        }
    }
}
