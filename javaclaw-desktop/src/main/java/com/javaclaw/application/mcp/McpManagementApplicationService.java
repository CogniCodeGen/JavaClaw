package com.javaclaw.application.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 配置和运行状态的工作区级业务入口。
 *
 * <p>实现可从任意线程调用，返回值均为不可变快照。方法可能访问数据库、网络或子进程，
 * 调用方必须显式选择托管 I/O 执行器。运行操作支持线程中断协作，但已经提交的配置不会
 * 因后续取消而回滚。配置快照可能含环境变量或 Header 明文，只能交给受信任的本地界面，
 * 不得记录日志或发送给模型。</p>
 */
public interface McpManagementApplicationService {

    Snapshot snapshot();

    OperationResult save(SaveCommand command);

    OperationResult delete(String serverName);

    OperationResult setEnabled(String serverName, boolean enabled);

    OperationResult start(String serverName);

    OperationResult restart(String serverName);

    OperationResult stop(String serverName);

    TestResult test(SaveCommand command);

    ImportPreview previewImport(String json, String fallbackName);

    OperationResult importJson(String json, String fallbackName);

    List<Template> templates();

    LogSnapshot log(String serverName);

    /**
     * 订阅运行状态变化。回调线程不固定且不得阻塞；返回句柄关闭后不再回调。
     */
    AutoCloseable observeRuntime(Runnable listener);

    enum Transport { STDIO, HTTP }

    enum State { STOPPED, STARTING, RUNNING, FAILED }

    record Tool(String name, String description) {
        public Tool {
            name = text(name);
            description = text(description);
        }
    }

    record Server(
            String name,
            Transport transport,
            String command,
            List<String> arguments,
            Map<String, String> environment,
            String url,
            Map<String, String> headers,
            boolean enabled,
            State state,
            List<Tool> tools,
            String startupError,
            long startedAtMs) {
        public Server {
            name = text(name);
            transport = transport == null ? Transport.STDIO : transport;
            command = text(command);
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
            environment = Map.copyOf(environment == null ? Map.of() : environment);
            url = text(url);
            headers = Map.copyOf(headers == null ? Map.of() : headers);
            state = state == null ? State.STOPPED : state;
            tools = List.copyOf(tools == null ? List.of() : tools);
            startupError = text(startupError);
        }

        public String launchSummary() {
            return transport == Transport.HTTP
                    ? url
                    : (command + " " + String.join(" ", arguments)).strip();
        }
    }

    record Snapshot(List<Server> servers, String storageDescription) {
        public Snapshot {
            servers = List.copyOf(servers == null ? List.of() : servers);
            storageDescription = text(storageDescription);
        }

        public Server require(String name) {
            String wanted = text(name);
            return servers.stream().filter(server -> server.name().equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new com.javaclaw.application.error.NotFoundException(
                            "未找到 MCP 服务器：" + wanted));
        }
    }

    record SaveCommand(
            String originalName,
            String name,
            Transport transport,
            String command,
            List<String> arguments,
            Map<String, String> environment,
            String url,
            Map<String, String> headers,
            boolean enabled) {
        public SaveCommand {
            originalName = text(originalName);
            name = text(name);
            transport = transport == null ? Transport.STDIO : transport;
            command = text(command);
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
            environment = Map.copyOf(environment == null ? Map.of() : environment);
            url = text(url);
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }
    }

    record OperationResult(Snapshot snapshot, boolean runtimeSucceeded, String runtimeMessage) {
        public OperationResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            runtimeMessage = text(runtimeMessage);
        }
    }

    record TestResult(
            boolean success,
            List<Tool> tools,
            long elapsedMs,
            String errorMessage,
            String serverName,
            String serverVersion) {
        public TestResult {
            tools = List.copyOf(tools == null ? List.of() : tools);
            errorMessage = text(errorMessage);
            serverName = text(serverName);
            serverVersion = text(serverVersion);
        }
    }

    record ImportPreview(List<SaveCommand> servers) {
        public ImportPreview {
            servers = List.copyOf(servers == null ? List.of() : servers);
        }
    }

    record Template(
            String id,
            String displayName,
            String description,
            String command,
            List<String> arguments,
            List<String> environmentKeys,
            String toolsHint) {
        public Template {
            id = text(id);
            displayName = text(displayName);
            description = text(description);
            command = text(command);
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
            environmentKeys = List.copyOf(environmentKeys == null ? List.of() : environmentKeys);
            toolsHint = text(toolsHint);
        }
    }

    record LogSnapshot(String serverName, String startupError, List<String> stderrLines) {
        public LogSnapshot {
            serverName = text(serverName);
            startupError = text(startupError);
            stderrLines = List.copyOf(stderrLines == null ? List.of() : stderrLines);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
