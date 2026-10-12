package com.javaclaw.infrastructure.mcp;

import com.javaclaw.application.mcp.McpConfigurationPort;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import com.javaclaw.mcp.McpConfigManager;
import com.javaclaw.mcp.McpServerConfig;

import java.util.List;
import java.util.Objects;

/** 将工作区 MCP 配置仓储适配到 Application 端口。 */
public final class McpConfigManagerAdapter implements McpConfigurationPort {

    private final McpConfigManager manager;

    public McpConfigManagerAdapter(McpConfigManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<Entry> list() {
        return manager.getAllServers().stream().map(McpConfigManagerAdapter::toEntry).toList();
    }

    @Override
    public void save(Entry entry) {
        manager.putServerChecked(toConfig(entry));
    }

    @Override
    public void saveAll(List<Entry> entries) {
        manager.putServersChecked(entries.stream().map(McpConfigManagerAdapter::toConfig).toList());
    }

    @Override
    public boolean delete(String name) {
        return manager.removeServerChecked(name);
    }

    @Override
    public String storageDescription() {
        return manager.getConfigFilePath();
    }

    static Entry toEntry(McpServerConfig config) {
        return new Entry(config.getName(), "http".equals(config.getTransport())
                ? Transport.HTTP : Transport.STDIO, config.getCommand(), config.getArgs(),
                config.getEnv(), config.getUrl(), config.getHeaders(), config.isEnabled());
    }

    static McpServerConfig toConfig(Entry entry) {
        return entry.transport() == Transport.HTTP
                ? new McpServerConfig(entry.name(), entry.url(), entry.headers(), entry.enabled())
                : new McpServerConfig(entry.name(), entry.command(), entry.arguments(),
                        entry.environment(), entry.enabled());
    }
}
