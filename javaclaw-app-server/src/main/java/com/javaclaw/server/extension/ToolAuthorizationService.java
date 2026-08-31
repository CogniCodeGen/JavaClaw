package com.javaclaw.server.extension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.agent.tool.BasicJsonSchema;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolAuthorizationGateway;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 用户确认的参数模板与运行时快照求交集；预授权仅适用于无人值守 Schedule 的 MCP 业务操作。 */
public final class ToolAuthorizationService implements ToolAuthorizationGateway, ToolAuthorizationUseCases {
    private static final Set<String> TEXT_FIELDS = Set.of("subject", "body", "text", "message", "content", "html");
    private final ToolAuthorizationRepository repository;
    private final WorkspaceRepository workspaces;
    private final Function<String, List<ToolAuthorityOption>> catalog;
    private final ObjectMapper json;

    /** 绑定 H2、工作区权威和真实发现目录；查询目录不能制造网络或新授权。 */
    public ToolAuthorizationService(
            ToolAuthorizationRepository repository,
            WorkspaceRepository workspaces,
            Function<String, List<ToolAuthorityOption>> catalog,
            ObjectMapper json) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.json = java.util.Objects.requireNonNull(json);
    }

    /** 查询最近真实发现的工具；空目录表示必须先完成当前工作区的 MCP 发现，而非支持虚构工具。 */
    public List<ToolAuthorityOption> options(String workspaceId) {
        workspace(workspaceId);
        return List.copyOf(catalog.apply(workspaceId));
    }

    /** 查询授权 metadata 与用户确认的模板，不查询 SecretStore。 */
    public List<ToolAuthorization> list(String workspaceId) {
        workspace(workspaceId);
        return repository.list(workspaceId);
    }

    /** 确认准确接收对象、模板、连接/Schema 版本、1–100 次和最长三十天后保存。 */
    public ToolAuthorization put(ToolAuthorization request, boolean confirmed, String key) {
        workspace(request.workspaceId());
        if (!confirmed) {
            throw new IllegalArgumentException("explicit bounded authorization confirmation is required");
        }
        ToolAuthorityOption option = options(request.workspaceId()).stream()
                .filter(value -> value.sourceId().equals(request.sourceId())
                        && value.toolName().equals(request.toolName())
                        && value.sourceRevision() == request.sourceRevision()
                        && value.schemaSha256().equals(request.schemaSha256()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tool discovery or revision changed"));
        Instant now = Instant.now();
        if (request.expiresAt() == null
                || !request.expiresAt().isAfter(now)
                || request.expiresAt().isAfter(now.plus(30, ChronoUnit.DAYS))
                || request.maximumUses() < 1
                || request.maximumUses() > 100
                || request.variableFields().size() > 6
                || !TEXT_FIELDS.containsAll(request.variableFields())) {
            throw new IllegalArgumentException("authorization requires finite expiry, uses and text-only variables");
        }
        JsonNode template = document(request.argumentTemplate());
        new BasicJsonSchema(json, option.inputSchemaJson()).validate(template);
        String receiver = request.recipientField();
        if (receiver == null
                || !template.path(receiver).isTextual()
                || template.path(receiver).asText().isBlank()
                || template.path(receiver).asText().length() > 500
                || request.variableFields().contains(receiver)) {
            throw new IllegalArgumentException("a fixed recipient field is required");
        }
        for (String field : request.variableFields()) {
            if (!template.path(field).isTextual()
                    || template.path(field).asText().length() > 4096) {
                throw new IllegalArgumentException("variable fields must exist as bounded text in the template");
            }
        }
        rejectCredentials(template);
        return repository.put(
                new ToolAuthorization(
                        request.id(),
                        request.workspaceId(),
                        request.sourceId(),
                        request.toolName(),
                        request.sourceRevision(),
                        option.schemaSha256(),
                        canonical(template).toString(),
                        receiver,
                        request.variableFields(),
                        request.maximumUses(),
                        0,
                        request.expiresAt(),
                        request.enabled(),
                        request.revision(),
                        now),
                request.revision(),
                key);
    }

    /** 撤销后下一次核销立即失败；已开始的外部操作不能假定可以回滚。 */
    public boolean disable(String id, long revision, String key) {
        return repository.disable(id, revision, key);
    }

    @Override
    public Optional<Receipt> consume(
            ToolExecutionContext context,
            RegisteredTool tool,
            JsonNode arguments,
            SandboxPolicy policy,
            String invocationId)
            throws Exception {
        if (tool.origin() != ToolOrigin.MCP
                || context.config().approvalPolicy() != ApprovalPolicy.NEVER
                || !"SCHEDULE".equals(context.config().attributes().get("profileKind"))
                || policy.mode() == SandboxMode.HOST_FULL_ACCESS
                || policy.network().mode() == NetworkPolicy.Mode.FULL) {
            return Optional.empty();
        }
        workspace(context.thread().workspaceId());
        tool.availability().verify();
        var identity = tool.availability().identity();
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        String schema = PromptHashes.sha256(tool.descriptor().inputSchemaJson());
        for (ToolAuthorization grant : repository.list(context.thread().workspaceId())) {
            if (!grant.enabled()
                    || !grant.expiresAt().isAfter(Instant.now())
                    || !grant.sourceId().equals(identity.get().sourceId())
                    || grant.sourceRevision() != identity.get().revision()
                    || !grant.toolName().equals(tool.descriptor().name())
                    || !grant.schemaSha256().equals(schema)
                    || !matches(grant, arguments)) {
                continue;
            }
            var receipt = repository.consume(
                    grant.id(),
                    grant.revision(),
                    invocationId,
                    PromptHashes.sha256(canonical(arguments).toString()),
                    Instant.now());
            if (receipt.isPresent()) {
                return receipt;
            }
        }
        return Optional.empty();
    }

    private boolean matches(ToolAuthorization grant, JsonNode arguments) {
        JsonNode template = document(grant.argumentTemplate());
        if (!arguments.isObject()
                || !template.properties().stream()
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(arguments.properties().stream()
                                .map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet()))) {
            return false;
        }
        for (var field : template.properties()) {
            JsonNode actual = arguments.get(field.getKey());
            if (grant.variableFields().contains(field.getKey())) {
                if (!actual.isTextual() || actual.asText().length() > 4096) {
                    return false;
                }
            } else if (!actual.equals(field.getValue())) {
                // 包括 cc/bcc、账号、目的地址和额外 header；不能通过新增字段绕过固定接收对象。
                return false;
            }
        }
        rejectCredentials(arguments);
        return true;
    }

    private JsonNode document(String source) {
        if (source == null || source.length() > 32_768) {
            throw new IllegalArgumentException("bounded argument template is required");
        }
        try {
            JsonNode value = json.readTree(source);
            if (value == null || !value.isObject() || value.size() > 32) {
                throw new IllegalArgumentException("argument template must be an object");
            }
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid argument template");
        }
    }

    private void rejectCredentials(JsonNode node) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                if (entry.getKey().matches("(?i).*(password|secret|token|api.?key|authorization|cookie).*")) {
                    throw new IllegalArgumentException("credentials cannot be placed in an authorization template");
                }
                rejectCredentials(entry.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectCredentials);
        } else if (node.isTextual()
                && (node.asText().contains("-----BEGIN ")
                        || node.asText().matches("(?s).*\\b(sk-[A-Za-z0-9_-]{16,}|Bearer\\s+[A-Za-z0-9._-]{16,}).*"))) {
            throw new IllegalArgumentException("secret-like values cannot be stored in an authorization");
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    private void workspace(String id) {
        var workspace = workspaces
                .find(new WorkspaceId(id))
                .orElseThrow(() -> new IllegalArgumentException("workspace not found"));
        if (workspace.locked()) {
            throw new IllegalStateException("workspace is locked");
        }
    }
}
