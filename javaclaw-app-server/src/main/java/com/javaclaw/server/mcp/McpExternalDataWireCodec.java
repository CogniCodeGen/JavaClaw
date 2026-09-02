package com.javaclaw.server.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpProgress;
import com.javaclaw.api.McpPromptArgument;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptMessage;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourceContent;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.protocol.CanonicalJson;

/** 将固定 MCP 2026-07-28 的 Resource 与 Prompt 结果转换为强类型外部数据。 */
final class McpExternalDataWireCodec {
    private static final int MAXIMUM_PAGE_ENTRIES = 200;
    private static final int MAXIMUM_CONTENTS = 64;

    private final CanonicalJson json;

    McpExternalDataWireCodec(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    CanonicalPayload pageParams(Optional<String> cursor, String progressToken) {
        return json.encode(new PageParams(Objects.requireNonNull(cursor, "cursor"), metadata(progressToken)));
    }

    CanonicalPayload resourceReadParams(String uri, String progressToken) {
        return json.encode(Map.of("uri", resourceUri(uri), "_meta", metadata(progressToken)));
    }

    CanonicalPayload promptGetParams(String name, Map<String, String> arguments, String progressToken) {
        return json.encode(new PromptGetParams(text(name, "name", 240), arguments(arguments), metadata(progressToken)));
    }

    McpResourcePage resourcePage(CanonicalPayload result, List<McpProgress> progress) {
        requireComplete(result);
        List<McpResourceDescriptor> resources =
                json.objectArrayField(result, "resources", MAXIMUM_PAGE_ENTRIES).stream()
                        .map(this::resource)
                        .toList();
        return new McpResourcePage(
                resources, json.textField(result, "nextCursor"), cacheScope(result), ttl(result), progress);
    }

    McpResourceReadResult resourceResult(CanonicalPayload result, List<McpProgress> progress) {
        requireComplete(result);
        List<McpResourceContent> contents = json.objectArrayField(result, "contents", MAXIMUM_CONTENTS).stream()
                .map(this::resourceContent)
                .toList();
        return new McpResourceReadResult(contents, cacheScope(result), ttl(result), progress);
    }

    McpPromptPage promptPage(CanonicalPayload result, List<McpProgress> progress) {
        requireComplete(result);
        List<McpPromptDescriptor> prompts = json.objectArrayField(result, "prompts", MAXIMUM_PAGE_ENTRIES).stream()
                .map(this::prompt)
                .toList();
        return new McpPromptPage(
                prompts, json.textField(result, "nextCursor"), cacheScope(result), ttl(result), progress);
    }

    McpPromptResult promptResult(CanonicalPayload result, List<McpProgress> progress) {
        requireComplete(result);
        List<McpPromptMessage> messages = json.objectArrayField(result, "messages", MAXIMUM_CONTENTS).stream()
                .map(this::promptMessage)
                .toList();
        return new McpPromptResult(json.textField(result, "description"), messages, progress);
    }

    private McpResourceDescriptor resource(CanonicalPayload value) {
        return new McpResourceDescriptor(
                requiredText(value, "name", 240),
                resourceUri(requiredText(value, "uri", 4_096)),
                json.textField(value, "title"),
                json.textField(value, "description"),
                json.textField(value, "mimeType"),
                json.integerField(value, "size"));
    }

    private McpResourceContent resourceContent(CanonicalPayload value) {
        Optional<String> textContent = json.textField(value, "text");
        Optional<String> blob = json.textField(value, "blob");
        return new McpResourceContent(
                resourceUri(requiredText(value, "uri", 4_096)), json.textField(value, "mimeType"), textContent, blob);
    }

    private McpPromptDescriptor prompt(CanonicalPayload value) {
        List<McpPromptArgument> arguments = json.objectArrayField(value, "arguments", 32).stream()
                .map(this::promptArgument)
                .toList();
        return new McpPromptDescriptor(
                requiredText(value, "name", 240),
                json.textField(value, "title"),
                json.textField(value, "description"),
                arguments);
    }

    private McpPromptArgument promptArgument(CanonicalPayload value) {
        return new McpPromptArgument(
                requiredText(value, "name", 240),
                json.textField(value, "description"),
                json.booleanField(value, "required").orElse(false));
    }

    private McpPromptMessage promptMessage(CanonicalPayload value) {
        String role = requiredText(value, "role", 20).toLowerCase(Locale.ROOT);
        McpSamplingRole checkedRole =
                switch (role) {
                    case "user" -> McpSamplingRole.USER;
                    case "assistant" -> McpSamplingRole.ASSISTANT;
                    default -> throw new IllegalArgumentException("MCP Prompt system role is forbidden");
                };
        CanonicalPayload content = json.objectField(value, "content")
                .orElseThrow(() -> new IllegalArgumentException("MCP Prompt content must be an object"));
        return new McpPromptMessage(checkedRole, content);
    }

    private void requireComplete(CanonicalPayload result) {
        String resultType = json.textField(result, "resultType")
                .orElseThrow(() -> new IllegalArgumentException("MCP 2026-07-28 resultType is required"));
        if (!"complete".equals(resultType)) {
            throw new IllegalArgumentException("MCP interactive external-data result is not accepted here");
        }
    }

    private McpCacheScope cacheScope(CanonicalPayload result) {
        return switch (requiredText(result, "cacheScope", 20)) {
            case "private" -> McpCacheScope.PRIVATE;
            case "public" -> McpCacheScope.PUBLIC;
            default -> throw new IllegalArgumentException("MCP cacheScope is invalid");
        };
    }

    private long ttl(CanonicalPayload result) {
        long value = json.integerField(result, "ttlMs")
                .orElseThrow(() -> new IllegalArgumentException("MCP 2026-07-28 ttlMs is required"));
        if (value < 0) {
            throw new IllegalArgumentException("MCP ttlMs must not be negative");
        }
        return value;
    }

    private String requiredText(CanonicalPayload value, String field, int maximum) {
        return text(
                json.textField(value, field)
                        .orElseThrow(() -> new IllegalArgumentException("MCP " + field + " is required")),
                field,
                maximum);
    }

    private static Map<String, String> arguments(Map<String, String> arguments) {
        Map<String, String> checked = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (checked.size() > 32) {
            throw new IllegalArgumentException("MCP Prompt arguments must not exceed 32 entries");
        }
        checked.forEach((name, value) -> {
            text(name, "argument name", 240);
            argumentValue(value);
        });
        return checked;
    }

    private static void argumentValue(String value) {
        String checked = Objects.requireNonNull(value, "argument value");
        if (checked.length() > 8_192 || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("argument value is invalid");
        }
    }

    private static Map<String, String> metadata(String progressToken) {
        return Map.of("progressToken", text(progressToken, "progressToken", 256));
    }

    private static String resourceUri(String value) {
        String checked = text(value, "uri", 4_096);
        java.net.URI uri = java.net.URI.create(checked);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("MCP Resource URI must be absolute");
        }
        return checked;
    }

    private static String text(String value, String name, int maximum) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty() || checked.length() > maximum || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private record PageParams(Optional<String> cursor, Map<String, String> _meta) {}

    private record PromptGetParams(String name, Map<String, String> arguments, Map<String, String> _meta) {}
}
