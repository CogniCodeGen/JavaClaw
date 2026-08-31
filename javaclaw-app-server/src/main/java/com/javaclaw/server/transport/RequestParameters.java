package com.javaclaw.server.transport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

final class RequestParameters {
    private RequestParameters() {}

    static String requiredText(JsonNode params, String name) {
        JsonNode value = object(params).get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return value.textValue().strip();
    }

    static String optionalText(JsonNode params, String name, String fallback) {
        JsonNode value = object(params).get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        String text = value.textValue().strip();
        return text.isEmpty() ? fallback : text;
    }

    static boolean optionalBoolean(JsonNode params, String name, boolean fallback) {
        JsonNode value = object(params).get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return value.booleanValue();
    }

    static long optionalLong(JsonNode params, String name, long fallback) {
        JsonNode value = object(params).get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.longValue();
    }

    static ThreadId threadId(JsonNode params) {
        return new ThreadId(requiredText(params, "threadId"));
    }

    static Path requiredAbsolutePath(JsonNode params, String name) {
        Path path = Path.of(requiredText(params, name));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return path.normalize();
    }

    static Path optionalAbsolutePath(JsonNode params, String name) {
        String value = optionalText(params, name, null);
        if (value == null) {
            return null;
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return path.normalize();
    }

    static List<TurnInput> inputs(JsonNode params, Path base) {
        JsonNode values = object(params).get("input");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("input must be a non-empty array");
        }
        List<TurnInput> result = new ArrayList<>();
        for (JsonNode value : values) {
            String type = requiredText(value, "type");
            result.add(
                    switch (type) {
                        case "text" -> new TurnInput.Text(requiredText(value, "text"));
                        case "attachment" ->
                            new TurnInput.AttachmentRef(
                                    requiredText(value, "sha256"),
                                    optionalText(value, "mediaType", "application/octet-stream"),
                                    optionalText(value, "displayName", "attachment"));
                        default -> throw new IllegalArgumentException("unsupported input type: " + type);
                    });
        }
        return List.copyOf(result);
    }

    static TurnInput steeringInput(JsonNode params) {
        JsonNode input = object(params).get("input");
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("input must be an object");
        }
        String type = requiredText(input, "type");
        return switch (type) {
            case "text" -> new TurnInput.Text(requiredText(input, "text"));
            case "attachment" ->
                new TurnInput.AttachmentRef(
                        requiredText(input, "sha256"),
                        optionalText(input, "mediaType", "application/octet-stream"),
                        optionalText(input, "displayName", "attachment"));
            default -> throw new IllegalArgumentException("unsupported input type: " + type);
        };
    }

    static TurnConfig turnConfig(JsonNode params, Path threadDirectory) {
        JsonNode config = object(params).get("config");
        if (config == null || config.isNull()) {
            config = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        config = object(config);
        if (config.has("workingDirectory") || config.has("sandboxPolicy")) {
            throw new IllegalArgumentException("clients cannot supply workingDirectory or sandboxPolicy");
        }
        Path cwd = threadDirectory.toAbsolutePath().normalize();
        ApprovalPolicy approvals =
                ApprovalPolicy.valueOf(optionalText(config, "approvalPolicy", ApprovalPolicy.ON_RISK.name()));
        SandboxMode requested = SandboxMode.valueOf(optionalText(config, "accessMode", SandboxMode.READ_ONLY.name()));
        if (requested == SandboxMode.HOST_FULL_ACCESS) {
            throw new IllegalArgumentException("HOST_FULL_ACCESS can only be granted by interactive approval");
        }
        Set<Path> protectedRoots = Set.of(cwd.resolve(".git"), cwd.resolve(".javaclaw"));
        SandboxPolicy policy = requested == SandboxMode.WORKSPACE_WRITE
                ? new SandboxPolicy(
                        SandboxMode.WORKSPACE_WRITE,
                        Set.of(cwd),
                        Set.of(cwd),
                        protectedRoots,
                        com.javaclaw.sandbox.api.NetworkPolicy.disabled(),
                        Set.of(),
                        java.time.Duration.ofMinutes(5),
                        SandboxPolicy.DEFAULT_OUTPUT_LIMIT)
                : SandboxPolicy.readOnly(Set.of(cwd), protectedRoots);
        return new TurnConfig(
                optionalText(config, "model", "gpt-5"),
                optionalText(config, "provider", "openai"),
                optionalText(config, "reasoningEffort", "medium"),
                cwd,
                policy,
                approvals,
                strings(config.get("enabledTools")),
                stringMap(config.get("attributes")));
    }

    static com.javaclaw.agent.conversation.ProfileRepository.ProfileDraft profileDraft(JsonNode params) {
        JsonNode value = object(params).has("profile") ? object(params).get("profile") : params;
        value = object(value);
        return new com.javaclaw.agent.conversation.ProfileRepository.ProfileDraft(
                optionalText(value, "id", null),
                requiredText(value, "name"),
                com.javaclaw.core.api.ProfileKind.valueOf(requiredText(value, "kind")),
                requiredText(value, "provider"),
                requiredText(value, "model"),
                optionalText(value, "systemPrompt", ""),
                strings(value.get("enabledTools")),
                SandboxMode.valueOf(optionalText(value, "requestedSandboxMode", SandboxMode.READ_ONLY.name())),
                Math.toIntExact(optionalLong(value, "maxIterations", 16)),
                Math.toIntExact(optionalLong(value, "maxModelCalls", 16)),
                stringMap(value.get("attributes")));
    }

    private static Path resolvePath(String value, Path base) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Objects.requireNonNull(base, "base").resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Set<String> strings(JsonNode node) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("value must be an array");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("array item must be a string");
            }
            result.add(value.textValue());
        });
        return Set.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("value must be an object");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("object values must be strings");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    private static JsonNode object(JsonNode node) {
        if (node == null || node.isNull()) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        return node;
    }
}
