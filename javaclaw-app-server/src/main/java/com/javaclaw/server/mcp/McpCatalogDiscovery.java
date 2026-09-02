package com.javaclaw.server.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.server.persistence.PersistenceException;

/** 固定协议下完成有界 MCP Catalog 分页发现。 */
final class McpCatalogDiscovery {
    private static final int MAXIMUM_PAGES = 50;
    private static final int MAXIMUM_ENTRIES = 10_000;

    private final McpRemotePort remote;

    McpCatalogDiscovery(McpRemotePort remote) {
        this.remote = remote;
    }

    List<McpCatalogEntry> discover(McpEndpoint endpoint) {
        return discover(endpoint, (pages, entries) -> {});
    }

    List<McpCatalogEntry> discover(McpEndpoint endpoint, BiConsumer<Integer, Integer> progress) {
        try {
            BiConsumer<Integer, Integer> checkedProgress = java.util.Objects.requireNonNull(progress, "progress");
            McpRemoteSession session = remote.initialize(endpoint, McpProtocol.VERSION, new CancellationSource());
            requireProtocol(session);
            List<McpCatalogEntry> entries = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            Set<String> identities = new HashSet<>();
            Optional<String> cursor = Optional.empty();
            for (int page = 0; page < MAXIMUM_PAGES; page++) {
                McpCatalogPage response = remote.catalog(endpoint, cursor, new CancellationSource());
                appendPage(entries, identities, response);
                checkedProgress.accept(page + 1, entries.size());
                if (response.nextCursor().isEmpty()) {
                    return List.copyOf(entries);
                }
                String next = response.nextCursor().orElseThrow();
                if (!cursors.add(next)) {
                    throw PersistenceException.invalidRequest("MCP Catalog cursor 循环");
                }
                cursor = Optional.of(next);
            }
            throw PersistenceException.invalidRequest("MCP Catalog 分页超过平台上限");
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP Catalog 发现失败", failure);
        }
    }

    private static void appendPage(List<McpCatalogEntry> entries, Set<String> identities, McpCatalogPage response) {
        if (entries.size() + response.entries().size() > MAXIMUM_ENTRIES) {
            throw PersistenceException.invalidRequest("MCP Catalog 条目超过平台上限");
        }
        for (McpCatalogEntry entry : response.entries()) {
            String identity = entry.kind().name() + ':' + entry.name();
            if (!identities.add(identity)) {
                throw PersistenceException.invalidRequest("MCP Catalog 包含重复条目");
            }
            entries.add(entry);
        }
    }

    private static void requireProtocol(McpRemoteSession session) {
        if (!McpProtocol.VERSION.equals(session.protocolVersion())) {
            throw PersistenceException.invalidRequest("MCP 固定协议版本不匹配，禁止降级");
        }
    }
}
