package com.javaclaw.server.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.protocol.CanonicalJson;

/** 将 MCP 三类分页目录规范化为 JavaClaw 单一 Catalog 流。 */
final class McpCatalogWireCodec {
    private static final int MAXIMUM_PAGE_ENTRIES = 200;
    private static final int MAXIMUM_CURSOR_BYTES = 4_096;
    private static final List<KindBinding> KINDS = List.of(
            new KindBinding(McpCatalogKind.TOOL, "tools/list", "tools"),
            new KindBinding(McpCatalogKind.PROMPT, "prompts/list", "prompts"),
            new KindBinding(McpCatalogKind.RESOURCE, "resources/list", "resources"));

    private final CanonicalJson json;
    private final CanonicalPayload genericOutputSchema;

    McpCatalogWireCodec(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
        genericOutputSchema = json.parse("{\"type\":\"object\"}");
    }

    Request request(Optional<String> cursor) {
        CursorState state = cursor.map(this::decodeCursor).orElseGet(() -> new CursorState(0, Optional.empty()));
        KindBinding binding = KINDS.get(state.kindIndex());
        return new Request(binding.method(), json.encode(new PageParams(state.remoteCursor())), state);
    }

    McpCatalogPage decode(Request request, CanonicalPayload result) {
        requireFixedPageMetadata(result);
        KindBinding binding = KINDS.get(request.state().kindIndex());
        List<McpCatalogEntry> entries =
                json.objectArrayField(result, binding.resultField(), MAXIMUM_PAGE_ENTRIES).stream()
                        .map(item -> entry(binding.kind(), item))
                        .toList();
        Optional<String> remoteNext = json.textField(result, "nextCursor").map(this::requireRemoteCursor);
        Optional<String> next = nextCursor(request.state().kindIndex(), remoteNext);
        return new McpCatalogPage(entries, next);
    }

    private void requireFixedPageMetadata(CanonicalPayload result) {
        if (!"complete".equals(json.textField(result, "resultType").orElse(""))) {
            throw new IllegalArgumentException("MCP 2026-07-28 catalog resultType must be complete");
        }
        String scope = json.textField(result, "cacheScope")
                .orElseThrow(() -> new IllegalArgumentException("MCP 2026-07-28 cacheScope is required"));
        if (!"private".equals(scope) && !"public".equals(scope)) {
            throw new IllegalArgumentException("MCP catalog cacheScope is invalid");
        }
        long ttl = json.integerField(result, "ttlMs")
                .orElseThrow(() -> new IllegalArgumentException("MCP 2026-07-28 ttlMs is required"));
        if (ttl < 0) {
            throw new IllegalArgumentException("MCP catalog ttlMs must not be negative");
        }
    }

    private McpCatalogEntry entry(McpCatalogKind kind, CanonicalPayload item) {
        String name = requiredText(item, "name");
        Optional<String> title = json.textField(item, "title").filter(value -> !value.isBlank());
        String description = json.textField(item, "description")
                .filter(value -> !value.isBlank())
                .orElseGet(() -> "MCP " + kind.name().toLowerCase(java.util.Locale.ROOT) + " " + name);
        if (kind != McpCatalogKind.TOOL) {
            return new McpCatalogEntry(
                    kind, name, title, description, Optional.empty(), Optional.empty(), Optional.empty());
        }
        CanonicalPayload input = json.objectField(item, "inputSchema")
                .orElseThrow(() -> new IllegalArgumentException("MCP Tool inputSchema is required"));
        CanonicalPayload output = json.objectField(item, "outputSchema").orElse(genericOutputSchema);
        return McpCatalogEntry.tool(name, title, description, input, output);
    }

    private String requiredText(CanonicalPayload payload, String field) {
        return json.textField(payload, field)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("MCP catalog " + field + " is required"));
    }

    private Optional<String> nextCursor(int kindIndex, Optional<String> remoteNext) {
        if (remoteNext.isPresent()) {
            return Optional.of(encodeCursor(new CursorState(kindIndex, remoteNext)));
        }
        int nextKind = kindIndex + 1;
        return nextKind < KINDS.size()
                ? Optional.of(encodeCursor(new CursorState(nextKind, Optional.empty())))
                : Optional.empty();
    }

    private String encodeCursor(CursorState state) {
        String encoded = state.remoteCursor()
                .map(value ->
                        Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .orElse("");
        return state.kindIndex() + "." + encoded;
    }

    private CursorState decodeCursor(String cursor) {
        String checked = Objects.requireNonNull(cursor, "cursor");
        if (checked.length() > MAXIMUM_CURSOR_BYTES || !checked.matches("[0-2]\\.[A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("MCP catalog cursor is invalid");
        }
        int kindIndex = checked.charAt(0) - '0';
        String encoded = checked.substring(2);
        if (encoded.isEmpty()) {
            return new CursorState(kindIndex, Optional.empty());
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new CursorState(
                    kindIndex, Optional.of(requireRemoteCursor(new String(decoded, StandardCharsets.UTF_8))));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("MCP catalog cursor is invalid", failure);
        }
    }

    private String requireRemoteCursor(String value) {
        String cursor = Objects.requireNonNull(value, "remoteCursor");
        if (cursor.isBlank()
                || cursor.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CURSOR_BYTES
                || cursor.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("MCP remote cursor is invalid");
        }
        return cursor;
    }

    record Request(String method, CanonicalPayload params, CursorState state) {}

    private record PageParams(Optional<String> cursor) {}

    private record KindBinding(McpCatalogKind kind, String method, String resultField) {}

    record CursorState(int kindIndex, Optional<String> remoteCursor) {
        CursorState {
            if (kindIndex < 0 || kindIndex >= KINDS.size()) {
                throw new IllegalArgumentException("kindIndex is outside the MCP catalog");
            }
            remoteCursor = Objects.requireNonNull(remoteCursor, "remoteCursor");
        }
    }
}
