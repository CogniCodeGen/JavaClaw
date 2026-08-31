package com.javaclaw.server.extension.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.extension.McpRepository;

/** Revision-aware MCP client factory and per-Turn discovery coordinator. */
public final class McpClientRegistry implements AutoCloseable {
    private final McpRepository repository;
    private final ObjectMapper json;
    private final McpCodec codec;
    private final McpTransportFactory transports;
    private final McpInputResolver inputResolver;
    private final ConcurrentHashMap<String, McpDiscovery> discoveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

    /** 绑定持久 MCP 配置、transport 工厂及 MRTR 处理器；null resolver 按拒绝处理，不建立全局授权会话。 */
    public McpClientRegistry(
            McpRepository repository,
            ObjectMapper json,
            McpCodec codec,
            McpTransportFactory transports,
            McpInputResolver inputResolver) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.json = Objects.requireNonNull(json, "json");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.inputResolver = inputResolver == null ? McpInputResolver.REJECT_ALL : inputResolver;
    }

    /** 按本 Turn 创建只读发现会话；逐 Server 隔离失败，返回的 Snapshot 持有客户端并须在 Turn 结束时关闭。 */
    public Snapshot snapshot(TurnExecutionContext turn) {
        ArrayList<Available> available = new ArrayList<>();
        ArrayList<Failure> failures = new ArrayList<>();
        for (McpRepository.McpRecord record : repository.list()) {
            if (!record.enabled()) {
                continue;
            }
            McpClient client = null;
            try {
                McpConfiguration configuration = McpConfiguration.parse(record, json);
                if (configuration.workspaceId() != null
                        && !configuration.workspaceId().equals(turn.thread().workspaceId())) {
                    continue;
                }
                client = open(configuration, turn, discoveryAuthority(turn));
                McpDiscovery discovery = client.discover();
                discoveries.put(configuration.id(), discovery);
                List<McpRemoteTool> tools = client.listTools();
                var names = McpToolNames.allocate(
                        configuration.id(),
                        tools.stream().map(McpRemoteTool::name).toList());
                var authority = tools.stream()
                        .map(tool -> {
                            String schema = tool.inputSchema().toString();
                            return new com.javaclaw.server.extension.ToolAuthorityOption(
                                    configuration.id(),
                                    names.get(tool.name()),
                                    tool.description(),
                                    configuration.revision(),
                                    com.javaclaw.agent.prompt.PromptHashes.sha256(schema),
                                    schema);
                        })
                        .toList();
                catalogs.put(
                        turn.thread().workspaceId() + ":" + configuration.id(),
                        new Catalog(turn.thread().workspaceId(), configuration, authority));
                available.add(new Available(configuration, discovery, tools, client));
            } catch (Exception failure) {
                discoveries.remove(record.id());
                catalogs.remove(turn.thread().workspaceId() + ":" + record.id());
                if (client != null) {
                    client.close();
                }
                String message =
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                failures.add(new Failure(safeId(record.id()), message));
            }
        }
        return new Snapshot(List.copyOf(available), List.copyOf(failures));
    }

    /** 先复核 enabled/revision/quarantine，再用指定 Turn 权限创建独立调用客户端；调用方负责关闭。 */
    public McpClient openInvocation(McpConfiguration configuration, TurnExecutionContext turn, SandboxPolicy authority)
            throws Exception {
        verifyEnabled(configuration.id(), configuration.revision());
        return open(configuration, turn, authority);
    }

    /** 读取最新配置并拒绝已删除、禁用、隔离或版本变化的来源；旧快照不构成永久执行授权。 */
    public void verifyEnabled(String id, long revision) {
        McpRepository.McpRecord current =
                repository.find(id).orElseThrow(() -> new NoSuchElementException("MCP server no longer exists: " + id));
        if (!current.enabled()) {
            throw new IllegalStateException("MCP server is disabled: " + id);
        }
        if (current.revision() != revision) {
            throw new IllegalStateException("MCP server configuration changed: " + id);
        }
        if ("QUARANTINED".equalsIgnoreCase(current.state())) {
            throw new IllegalStateException("MCP server is quarantined: " + id);
        }
    }

    /** 读取最近一次成功发现的结果；不存在时返回 Optional.empty，不触发网络请求。 */
    public Optional<McpDiscovery> lastDiscovery(String id) {
        return Optional.ofNullable(discoveries.get(id));
    }

    /** 读取本工作区真实发现且配置仍有效的工具；配置变更/禁用立即排除，不在查询时执行未知代码。 */
    public List<com.javaclaw.server.extension.ToolAuthorityOption> authorityOptions(String workspaceId) {
        var result = new ArrayList<com.javaclaw.server.extension.ToolAuthorityOption>();
        for (Catalog catalog : catalogs.values()) {
            if (!catalog.workspaceId().equals(workspaceId)) {
                continue;
            }
            try {
                verifyEnabled(
                        catalog.configuration().id(), catalog.configuration().revision());
                result.addAll(catalog.options());
            } catch (RuntimeException revoked) {
                // 过期发现不是授权依据；新 Turn 完成真实发现后才重新出现在可选项。
            }
        }
        return result.stream()
                .sorted(java.util.Comparator.comparing(com.javaclaw.server.extension.ToolAuthorityOption::toolName))
                .toList();
    }

    /** 清空最近发现结果；不销毁仍由 Turn Snapshot 持有的客户端。 */
    public void invalidate() {
        discoveries.clear();
        catalogs.clear();
    }

    @Override
    public void close() {
        discoveries.clear();
        catalogs.clear();
    }

    private record Catalog(
            String workspaceId,
            McpConfiguration configuration,
            List<com.javaclaw.server.extension.ToolAuthorityOption> options) {}

    private McpClient open(McpConfiguration configuration, TurnExecutionContext turn, SandboxPolicy authority)
            throws Exception {
        McpTransport transport = transports.open(configuration, turn, authority);
        try {
            return new McpClient(configuration.id(), configuration.revision(), transport, codec, json, inputResolver);
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    private static SandboxPolicy discoveryAuthority(TurnExecutionContext turn) {
        SandboxPolicy authority = turn.turn().config().sandboxPolicy();
        return new SandboxPolicy(
                SandboxMode.READ_ONLY,
                authority.readableRoots(),
                java.util.Set.of(),
                authority.protectedRoots(),
                NetworkPolicy.disabled(),
                authority.inheritedEnvironment(),
                authority.timeout(),
                authority.outputLimitBytes());
    }

    private static String safeId(String value) {
        String result = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (result.isEmpty() || !Character.isLetter(result.charAt(0))) {
            result = "mcp_" + result;
        }
        return result.length() <= 160 ? result : result.substring(0, 160);
    }

    /**
     * 一个 Turn 的可用 MCP 客户端和独立失败结果；close 释放所有可用客户端。
     *
     * @param available 成功发现的配置/客户端列表，生产调用提供固定集合
     * @param failures 失败来源列表，单项失败不影响其他 Server
     */
    public record Snapshot(List<Available> available, List<Failure> failures) implements AutoCloseable {
        @Override
        public void close() {
            available.forEach(value -> value.client().close());
        }
    }

    /**
     * 固定修订的 MCP 配置、发现结果与其对应客户端。
     *
     * @param configuration 本次发现采用的 MCP 配置快照
     * @param discovery 本次能力发现结果
     * @param tools 本次成功发现的工具列表
     * @param client 由外层 Snapshot 管理生命周期的客户端
     */
    public record Available(
            McpConfiguration configuration, McpDiscovery discovery, List<McpRemoteTool> tools, McpClient client) {}

    /**
     * 单个 MCP Server 发现失败的诊断表示，发布前需脱敏。
     *
     * @param serverId 失败 Server 的安全展示标识
     * @param message 失败摘要，尚可能需要统一脱敏
     */
    public record Failure(String serverId, String message) {}
}
