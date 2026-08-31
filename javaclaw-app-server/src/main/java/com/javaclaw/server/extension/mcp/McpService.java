package com.javaclaw.server.extension.mcp;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.server.extension.McpRepository;
import com.javaclaw.server.security.SecretStore;

/** MCP management use cases. Values returned here contain metadata, never secret material. */
public final class McpService implements McpUseCases, AutoCloseable {
    private final McpRepository repository;
    private final McpClientRegistry clients;
    private final SecretStore secrets;
    private final ObjectMapper json;
    private final McpAuthorizationService authorization;

    /** 装配配置仓库、动态客户端、凭据和 OAuth 用例；只对外暴露 metadata 与发现状态。 */
    public McpService(
            McpRepository repository,
            McpClientRegistry clients,
            SecretStore secrets,
            ObjectMapper json,
            McpAuthorizationService authorization) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.json = Objects.requireNonNull(json, "json");
        this.authorization = authorization == null ? McpAuthorizationService.UNAVAILABLE : authorization;
    }

    @Override
    public McpDiscoveryStatus discover(String id) {
        McpRepository.McpRecord record = require(id);
        McpDiscovery discovery = clients.lastDiscovery(id).orElse(null);
        return new McpDiscoveryStatus(record.id(), record.revision(), record.enabled(), record.state(), discovery);
    }

    @Override
    public SecretStore.SecretMetadata setCredential(String id, char[] credential, String idempotencyKey) {
        McpConfiguration configuration = configuration(id);
        if (configuration.authentication().type() != McpConfiguration.AuthenticationType.BEARER
                && configuration.authentication().type() != McpConfiguration.AuthenticationType.API_KEY) {
            throw new IllegalStateException("MCP server does not use a static credential");
        }
        char[] value = Objects.requireNonNull(credential, "credential").clone();
        try {
            SecretStore.SecretMetadata metadata =
                    secrets.put(namespace(id), configuration.authentication().credentialName(), value, idempotencyKey);
            clients.invalidate();
            return metadata;
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public java.util.Optional<SecretStore.SecretMetadata> credentialMetadata(String id) {
        String name = configuration(id).authentication().credentialName();
        return name == null ? java.util.Optional.empty() : secrets.metadata(namespace(id), name);
    }

    @Override
    public boolean clearCredential(String id, long expectedRevision, String idempotencyKey) {
        McpConfiguration configuration = configuration(id);
        String name = configuration.authentication().credentialName();
        if (name == null) {
            throw new IllegalStateException("MCP server has no static credential metadata");
        }
        boolean removed = secrets.remove(namespace(id), name, expectedRevision, idempotencyKey);
        clients.invalidate();
        return removed;
    }

    @Override
    public McpAuthorizationService.Authorization startAuthorization(String id) throws Exception {
        McpConfiguration configuration = configuration(id);
        if (configuration.authentication().type() != McpConfiguration.AuthenticationType.OAUTH) {
            throw new IllegalStateException("MCP server is not configured for OAuth");
        }
        return authorization.start(configuration);
    }

    @Override
    public boolean cancelAuthorization(String authorizationId) {
        return authorization.cancel(authorizationId);
    }

    @Override
    public AutoCloseable onAuthorizationStatus(Consumer<McpAuthorizationService.AuthorizationStatus> listener) {
        return authorization.onStatus(listener);
    }

    @Override
    public void close() {
        authorization.close();
    }

    private McpConfiguration configuration(String id) {
        return McpConfiguration.parse(require(id), json);
    }

    private McpRepository.McpRecord require(String id) {
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("MCP server not found: " + id));
    }

    private static String namespace(String id) {
        return "mcp:" + id;
    }
}
