package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.mcp.McpOAuthCoordinator;
import com.javaclaw.server.mcp.McpService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;

/** MCP 管理方法到 Host 应用服务的薄 RPC registrar。 */
public final class McpRpcHandlers {
    private final McpService service;
    private final McpOAuthCoordinator oauth;
    private final ExtensionCatalogRepository catalog;
    private final CanonicalJson json;

    /**
     * 创建 MCP registrar。
     *
     * @param service MCP Host 应用服务
     * @param oauth OAuth 2.1 隔离 Browser 与持久状态组合服务
     * @param catalog MCP 平台能力的实时启停目录
     * @param json 规范 JSON codec
     */
    public McpRpcHandlers(
            McpService service, McpOAuthCoordinator oauth, ExtensionCatalogRepository catalog, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 MCP Protocol v2 方法。
     *
     * @param routes Router Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register("mcp/endpoint/list", this::list)
                .register("mcp/endpoint/read", this::read)
                .register("mcp/endpoint/history", this::history)
                .register("mcp/endpoint/create", this::create)
                .register("mcp/endpoint/update", this::update)
                .register("mcp/stdio/register", this::registerStdio)
                .register("mcp/endpoint/enable", params -> state(params, McpEndpointState.ENABLED, "enable"))
                .register("mcp/endpoint/disable", params -> state(params, McpEndpointState.DISABLED, "disable"))
                .register("mcp/health/read", this::health)
                .register("mcp/health/probe", this::probe)
                .register("mcp/catalog/list", this::catalog)
                .register("mcp/catalog/refresh/read", this::refreshRead)
                .register("mcp/catalog/refresh", this::refresh)
                .register("mcp/resource/list", this::resources)
                .register("mcp/resource/read", this::resourceRead)
                .register("mcp/prompt/list", this::prompts)
                .register("mcp/prompt/get", this::promptGet)
                .register("mcp/oauth/read", this::oauthRead)
                .register("mcp/oauth/start", this::oauthStart)
                .register("mcp/oauth/cancel", this::oauthCancel);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        McpRpcContracts.WorkspaceQuery query = json.decode(params, McpRpcContracts.WorkspaceQuery.class);
        return json.encode(new McpRpcContracts.EndpointListResult(service.list(query.workspaceId())));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        McpRpcContracts.EndpointQuery query = json.decode(params, McpRpcContracts.EndpointQuery.class);
        return json.encode(service.requireLatest(query.endpointId()));
    }

    private CanonicalPayload history(CanonicalPayload params) {
        McpRpcContracts.EndpointQuery query = json.decode(params, McpRpcContracts.EndpointQuery.class);
        return json.encode(new McpRpcContracts.EndpointListResult(service.history(query.endpointId())));
    }

    private CanonicalPayload create(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.EndpointWritePayload payload =
                json.decode(command.payload(), McpRpcContracts.EndpointWritePayload.class);
        return json.encode(service.createHttps(
                CommandIdentity.from("mcp/endpoint/create", command, json), payload.endpointId(), payload.spec()));
    }

    private CanonicalPayload update(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.EndpointWritePayload payload =
                json.decode(command.payload(), McpRpcContracts.EndpointWritePayload.class);
        return json.encode(service.updateHttps(
                CommandIdentity.from("mcp/endpoint/update", command, json), payload.endpointId(), payload.spec()));
    }

    private CanonicalPayload registerStdio(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.SignedBundleRegisterPayload payload =
                json.decode(command.payload(), McpRpcContracts.SignedBundleRegisterPayload.class);
        return json.encode(
                service.registerSignedBundle(CommandIdentity.from("mcp/stdio/register", command, json), payload));
    }

    private CanonicalPayload state(CanonicalPayload params, McpEndpointState state, String operation) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.EndpointQuery payload = json.decode(command.payload(), McpRpcContracts.EndpointQuery.class);
        return json.encode(service.setState(
                CommandIdentity.from("mcp/endpoint/" + operation, command, json), payload.endpointId(), state));
    }

    private CanonicalPayload health(CanonicalPayload params) {
        McpRpcContracts.EndpointQuery query = json.decode(params, McpRpcContracts.EndpointQuery.class);
        return json.encode(service.health(query.endpointId()));
    }

    private CanonicalPayload probe(CanonicalPayload params) {
        requireEnabled();
        McpRpcContracts.EndpointQuery query = json.decode(params, McpRpcContracts.EndpointQuery.class);
        return json.encode(service.probe(query.endpointId()));
    }

    private CanonicalPayload catalog(CanonicalPayload params) {
        McpRpcContracts.CatalogQuery query = json.decode(params, McpRpcContracts.CatalogQuery.class);
        return json.encode(new McpRpcContracts.CatalogResult(
                service.catalog(query.endpointId(), query.kind(), query.offset(), query.limit())));
    }

    private CanonicalPayload refresh(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.EndpointQuery payload = json.decode(command.payload(), McpRpcContracts.EndpointQuery.class);
        return json.encode(service.refreshCatalog(
                CommandIdentity.from("mcp/catalog/refresh", command, json), payload.endpointId()));
    }

    private CanonicalPayload refreshRead(CanonicalPayload params) {
        McpRpcContracts.EndpointQuery query = json.decode(params, McpRpcContracts.EndpointQuery.class);
        return json.encode(new McpRpcContracts.CatalogRefreshResult(service.catalogRefresh(query.endpointId())));
    }

    private CanonicalPayload resources(CanonicalPayload params) {
        requireEnabled();
        McpRpcContracts.ExternalPageQuery query = json.decode(params, McpRpcContracts.ExternalPageQuery.class);
        return json.encode(
                new McpRpcContracts.ResourcePageResult(service.resources(query.endpointId(), query.cursor())));
    }

    private CanonicalPayload resourceRead(CanonicalPayload params) {
        requireEnabled();
        McpRpcContracts.ResourceReadQuery query = json.decode(params, McpRpcContracts.ResourceReadQuery.class);
        return json.encode(
                new McpRpcContracts.ResourceReadResult(service.readResource(query.endpointId(), query.uri())));
    }

    private CanonicalPayload prompts(CanonicalPayload params) {
        requireEnabled();
        McpRpcContracts.ExternalPageQuery query = json.decode(params, McpRpcContracts.ExternalPageQuery.class);
        return json.encode(new McpRpcContracts.PromptPageResult(service.prompts(query.endpointId(), query.cursor())));
    }

    private CanonicalPayload promptGet(CanonicalPayload params) {
        requireEnabled();
        McpRpcContracts.PromptGetQuery query = json.decode(params, McpRpcContracts.PromptGetQuery.class);
        return json.encode(new McpRpcContracts.PromptResult(
                service.getPrompt(query.endpointId(), query.name(), query.arguments())));
    }

    private CanonicalPayload oauthRead(CanonicalPayload params) {
        McpRpcContracts.OAuthQuery query = json.decode(params, McpRpcContracts.OAuthQuery.class);
        java.util.Optional<com.javaclaw.api.McpOAuthAuthorization> result = query.authorizationId()
                        .isPresent()
                ? java.util.Optional.of(oauth.require(query.authorizationId().orElseThrow()))
                : oauth.latest(query.endpointId().orElseThrow());
        return json.encode(new McpRpcContracts.OAuthResult(result));
    }

    private CanonicalPayload oauthStart(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.OAuthStartPayload payload =
                json.decode(command.payload(), McpRpcContracts.OAuthStartPayload.class);
        return json.encode(oauth.start(CommandIdentity.from("mcp/oauth/start", command, json), payload.endpointId()));
    }

    private CanonicalPayload oauthCancel(CanonicalPayload params) {
        requireEnabled();
        WriteCommand command = json.decode(params, WriteCommand.class);
        McpRpcContracts.OAuthQuery payload = json.decode(command.payload(), McpRpcContracts.OAuthQuery.class);
        return json.encode(oauth.cancel(
                CommandIdentity.from("mcp/oauth/cancel", command, json),
                payload.authorizationId()
                        .orElseThrow(() -> new IllegalArgumentException("OAuth cancel requires authorizationId"))));
    }

    private void requireEnabled() {
        catalog.requireEnabled(new ExtensionId(BuiltinExtensionIds.MCP));
    }
}
