package com.javaclaw.server.mcp;

import java.util.Set;

import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpFrozenTool;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.server.persistence.PersistenceException;

/** MCP Catalog Tool 与 Core Tool 描述之间的无状态映射和一致性校验。 */
final class McpToolMapping {
    static final String PRODUCER_PREFIX = "mcp.";

    private McpToolMapping() {}

    static ToolDescriptor descriptor(McpEndpoint endpoint, McpCatalogEntry entry) {
        McpFrozenTool frozen = frozen(endpoint, entry);
        return new ToolDescriptor(
                new ToolIdentity(PRODUCER_PREFIX + endpoint.id(), entry.name(), frozen.toolRevision()),
                entry.description(),
                entry.inputSchema().orElseThrow(),
                entry.outputSchema().orElseThrow(),
                ToolRisk.NETWORK,
                Set.of("mcp", endpoint.id()));
    }

    static McpFrozenTool frozen(McpEndpoint endpoint, McpCatalogEntry entry) {
        return new McpFrozenTool(
                endpoint.id(),
                endpoint.revision(),
                endpoint.catalogRevision(),
                entry.name(),
                entry.schemaHash().orElseThrow());
    }

    static void requireFrozenDescriptor(ToolDescriptor descriptor, McpCatalogEntry entry, McpFrozenTool frozen) {
        boolean matches = descriptor.identity().revision() == frozen.toolRevision()
                && descriptor
                        .inputSchema()
                        .sha256()
                        .equals(entry.inputSchema().orElseThrow().sha256())
                && descriptor
                        .outputSchema()
                        .sha256()
                        .equals(entry.outputSchema().orElseThrow().sha256());
        if (!matches) {
            throw PersistenceException.invalidRequest("MCP Tool Catalog 或 Schema 已变化");
        }
    }

    static String endpointId(ToolIdentity identity) {
        String producer = java.util.Objects.requireNonNull(identity, "identity").producerId();
        if (!producer.startsWith(PRODUCER_PREFIX)) {
            throw PersistenceException.invalidRequest("Tool 不是 MCP 来源");
        }
        return identifier(producer.substring(PRODUCER_PREFIX.length()));
    }

    private static String identifier(String value) {
        String id = java.util.Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("MCP id contains unsupported characters");
        }
        return id;
    }
}
