package com.javaclaw.application.mcp;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** MCP 配置、运行和导入流程的应用服务实现；不保存任何页面状态。 */
public final class McpManagementUseCase implements McpManagementApplicationService {

    private final McpConfigurationPort configurations;
    private final McpRuntimePort runtime;
    private final McpImportPort importer;
    private final McpTemplatePort templateCatalog;

    public McpManagementUseCase(
            McpConfigurationPort configurations,
            McpRuntimePort runtime,
            McpImportPort importer,
            McpTemplatePort templateCatalog) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.templateCatalog = Objects.requireNonNull(templateCatalog, "templateCatalog");
    }

    @Override
    public Snapshot snapshot() {
        List<Server> servers = configurations.list().stream().map(this::toServer).toList();
        return new Snapshot(servers, configurations.storageDescription());
    }

    @Override
    public OperationResult save(SaveCommand command) {
        McpConfigurationPort.Entry entry = validated(command, true);
        configurations.save(entry);
        if (!entry.enabled()) {
            runtime.stop(entry.name());
            return success("配置已保存，服务器保持停止");
        }
        boolean started = runtime.restart(entry);
        return result(started, started ? "配置已保存并应用" : failureMessage(entry.name()));
    }

    @Override
    public OperationResult delete(String serverName) {
        String name = required(serverName, "服务器名称");
        requireEntry(name);
        if (!configurations.delete(name)) throw new NotFoundException("未找到 MCP 服务器：" + name);
        runtime.stop(name);
        return success("服务器已删除");
    }

    @Override
    public OperationResult setEnabled(String serverName, boolean enabled) {
        McpConfigurationPort.Entry existing = requireEntry(serverName);
        McpConfigurationPort.Entry updated = new McpConfigurationPort.Entry(
                existing.name(), existing.transport(), existing.command(), existing.arguments(),
                existing.environment(), existing.url(), existing.headers(), enabled);
        configurations.save(updated);
        if (!enabled) {
            runtime.stop(updated.name());
            return success("服务器已禁用");
        }
        boolean started = runtime.start(updated);
        return result(started, started ? "服务器已启用并启动" : failureMessage(updated.name()));
    }

    @Override
    public OperationResult start(String serverName) {
        McpConfigurationPort.Entry existing = requireEntry(serverName);
        if (!existing.enabled()) {
            existing = new McpConfigurationPort.Entry(
                    existing.name(), existing.transport(), existing.command(), existing.arguments(),
                    existing.environment(), existing.url(), existing.headers(), true);
            configurations.save(existing);
        }
        boolean started = runtime.start(existing);
        return result(started, started ? "服务器已启动" : failureMessage(existing.name()));
    }

    @Override
    public OperationResult restart(String serverName) {
        McpConfigurationPort.Entry existing = requireEntry(serverName);
        boolean started = runtime.restart(existing);
        return result(started, started ? "服务器已重启" : failureMessage(existing.name()));
    }

    @Override
    public OperationResult stop(String serverName) {
        String name = requireEntry(serverName).name();
        runtime.stop(name);
        return success("服务器已停止");
    }

    @Override
    public TestResult test(SaveCommand command) {
        McpRuntimePort.Test result = runtime.test(validated(command, false));
        return new TestResult(result.success(), result.tools(), result.elapsedMs(),
                result.errorMessage(), result.serverName(), result.serverVersion());
    }

    @Override
    public ImportPreview previewImport(String json, String fallbackName) {
        return new ImportPreview(importer.parse(json, fallbackName).stream()
                .map(entry -> toCommand(entry, ""))
                .map(command -> toCommand(validated(command, false), ""))
                .toList());
    }

    @Override
    public OperationResult importJson(String json, String fallbackName) {
        List<McpConfigurationPort.Entry> entries = previewImport(json, fallbackName).servers()
                .stream().map(command -> validated(command, false)).toList();
        if (entries.isEmpty()) throw new ValidationException("JSON 中未发现任何 MCP 服务器");
        configurations.saveAll(entries);
        int succeeded = 0;
        int failed = 0;
        for (McpConfigurationPort.Entry entry : entries) {
            if (!entry.enabled()) continue;
            if (runtime.restart(entry)) succeeded++;
            else failed++;
        }
        String message = failed == 0
                ? "已导入 " + entries.size() + " 个服务器，其中 " + succeeded + " 个已启动"
                : "已导入 " + entries.size() + " 个服务器；" + succeeded + " 个启动成功，"
                        + failed + " 个启动失败";
        return result(failed == 0, message);
    }

    @Override
    public List<Template> templates() {
        return templateCatalog.list();
    }

    @Override
    public LogSnapshot log(String serverName) {
        String name = requireEntry(serverName).name();
        McpRuntimePort.Status status = runtime.status(name);
        return new LogSnapshot(name, status.startupError(), runtime.stderr(name));
    }

    @Override
    public AutoCloseable observeRuntime(Runnable listener) {
        return runtime.observe(Objects.requireNonNull(listener, "listener"));
    }

    private Server toServer(McpConfigurationPort.Entry entry) {
        McpRuntimePort.Status status = runtime.status(entry.name());
        return new Server(entry.name(), entry.transport(), entry.command(), entry.arguments(),
                entry.environment(), entry.url(), entry.headers(), entry.enabled(), status.state(),
                status.tools(), status.startupError(), status.startedAtMs());
    }

    private McpConfigurationPort.Entry validated(SaveCommand command, boolean checkConflict) {
        Objects.requireNonNull(command, "command");
        String name = required(command.name(), "服务器名称");
        String original = command.originalName().strip();
        if (!original.isEmpty() && !original.equals(name)) {
            throw new ValidationException("编辑服务器时不能修改唯一名称");
        }
        if (checkConflict && !original.isEmpty() && find(original) == null) {
            throw new NotFoundException("待编辑的 MCP 服务器已不存在：" + original);
        }
        if (checkConflict && original.isEmpty() && find(name) != null) {
            throw new ConflictException("MCP 服务器名称已存在：" + name);
        }
        Map<String, String> values = command.transport() == Transport.HTTP
                ? normalizeMap(command.headers(), "Header")
                : normalizeMap(command.environment(), "环境变量");
        if (command.transport() == Transport.HTTP) {
            String url = validHttpUrl(command.url());
            return new McpConfigurationPort.Entry(name, Transport.HTTP, "", List.of(), Map.of(),
                    url, values, command.enabled());
        }
        String executable = required(command.command(), "stdio 启动命令");
        List<String> args = command.arguments().stream().map(String::strip)
                .filter(value -> !value.isEmpty()).toList();
        return new McpConfigurationPort.Entry(name, Transport.STDIO, executable, args, values,
                "", Map.of(), command.enabled());
    }

    private McpConfigurationPort.Entry requireEntry(String name) {
        String normalized = required(name, "服务器名称");
        McpConfigurationPort.Entry entry = find(normalized);
        if (entry == null) throw new NotFoundException("未找到 MCP 服务器：" + normalized);
        return entry;
    }

    private McpConfigurationPort.Entry find(String name) {
        return configurations.list().stream().filter(entry -> entry.name().equals(name))
                .findFirst().orElse(null);
    }

    private OperationResult success(String message) {
        return result(true, message);
    }

    private OperationResult result(boolean succeeded, String message) {
        return new OperationResult(snapshot(), succeeded, message);
    }

    private String failureMessage(String name) {
        String detail = runtime.status(name).startupError();
        return detail == null || detail.isBlank() ? "服务器启动失败" : detail;
    }

    private static SaveCommand toCommand(McpConfigurationPort.Entry entry, String originalName) {
        return new SaveCommand(originalName, entry.name(), entry.transport(), entry.command(),
                entry.arguments(), entry.environment(), entry.url(), entry.headers(), entry.enabled());
    }

    private static String required(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new ValidationException(label + "不能为空");
        return normalized;
    }

    private static String validHttpUrl(String value) {
        String normalized = required(value, "MCP 端点 URL");
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported endpoint");
            }
            return uri.toString();
        } catch (IllegalArgumentException failure) {
            throw new ValidationException("MCP 端点需为有效的 http:// 或 https:// URL");
        }
    }

    private static Map<String, String> normalizeMap(Map<String, String> source, String label) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            if (key.isEmpty()) continue;
            if (result.putIfAbsent(key, entry.getValue() == null ? "" : entry.getValue()) != null) {
                throw new ValidationException(label + " 名称重复：" + key);
            }
        }
        return result;
    }
}
