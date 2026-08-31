package com.javaclaw.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.protocol.WireAttachment;
import com.javaclaw.protocol.WireAttachmentUpload;
import com.javaclaw.protocol.WireAutomation;
import com.javaclaw.protocol.WireEvent;
import com.javaclaw.protocol.WireItem;
import com.javaclaw.protocol.WireKnowledgeHit;
import com.javaclaw.protocol.WireKnowledgeSource;
import com.javaclaw.protocol.WireMcpServer;
import com.javaclaw.protocol.WireMemory;
import com.javaclaw.protocol.WirePlugin;
import com.javaclaw.protocol.WirePluginTrustKey;
import com.javaclaw.protocol.WireProfile;
import com.javaclaw.protocol.WireProvider;
import com.javaclaw.protocol.WireSchedule;
import com.javaclaw.protocol.WireSecretMetadata;
import com.javaclaw.protocol.WireSkill;
import com.javaclaw.protocol.WireThread;
import com.javaclaw.protocol.WireThreadSnapshot;
import com.javaclaw.protocol.WireTurn;
import com.javaclaw.protocol.WireWorkspace;

/** Package-private JSON-RPC adapter. Public SDK types must never leak from this class. */
final class ProtocolClient implements AutoCloseable {
    CompletableFuture<com.javaclaw.protocol.WirePromptPreview> previewPrompt(String profileId, String workspaceId) {
        ObjectNode params = object().put("profileId", profileId).put("workspaceId", workspaceId);
        return request(RpcMethods.PROFILE_PROMPT_PREVIEW, params, com.javaclaw.protocol.WirePromptPreview.class);
    }

    CompletableFuture<WireTurn> optimizePrompt(
            String threadId, String profileId, String draft, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object().put("threadId", threadId)
                .put("profileId", profileId)
                .put("draft", draft)
                .put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.PROFILE_PROMPT_OPTIMIZE, params, WireTurn.class);
    }

    public static final long MAX_ATTACHMENT_BYTES = 256L * 1024 * 1024;
    public static final int ATTACHMENT_CHUNK_BYTES = 1024 * 1024;
    private final RpcConnection connection;
    private final ObjectMapper json;

    ProtocolClient(RpcConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.json = connection.codec().mapper();
    }

    public CompletableFuture<InitializeResult> initialize(String clientName, String version) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new InitializeParams.ClientInfo(clientName, clientName, version),
                Map.of("notifications", true));
        return connection
                .request(RpcMethods.INITIALIZE, json.valueToTree(params))
                .thenApply(node -> convert(node, InitializeResult.class))
                .thenApply(result -> {
                    connection.notify(RpcMethods.INITIALIZED, JsonNodeFactory.instance.objectNode());
                    return result;
                });
    }

    public CompletableFuture<List<WireWorkspace>> listWorkspaces() {
        return connection
                .request(RpcMethods.WORKSPACE_LIST, object())
                .thenApply(node -> list(node, WireWorkspace.class));
    }

    CompletableFuture<JsonNode> listModels() {
        return connection.request(RpcMethods.MODEL_LIST, object());
    }

    CompletableFuture<JsonNode> listTools() {
        return connection.request(RpcMethods.TOOL_LIST, object());
    }

    public CompletableFuture<WireWorkspace> createWorkspace(String name, Path root, String idempotencyKey) {
        Objects.requireNonNull(root, "root");
        ObjectNode params = object();
        params.put("name", name);
        params.put("root", root.toAbsolutePath().normalize().toString());
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.WORKSPACE_CREATE, params, WireWorkspace.class);
    }

    public CompletableFuture<WireWorkspace> readWorkspace(String workspaceId) {
        return request(RpcMethods.WORKSPACE_READ, workspaceParams(workspaceId), WireWorkspace.class);
    }

    CompletableFuture<com.javaclaw.protocol.WireAgentsInstructionResolution> resolveInstructions(String workspaceId) {
        return request(
                RpcMethods.WORKSPACE_INSTRUCTIONS_RESOLVE,
                workspaceParams(workspaceId),
                com.javaclaw.protocol.WireAgentsInstructionResolution.class);
    }

    public CompletableFuture<WireWorkspace> updateWorkspace(
            String workspaceId, String name, long expectedRevision, String idempotencyKey) {
        ObjectNode params = workspaceParams(workspaceId);
        params.put("name", name);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.WORKSPACE_UPDATE, params, WireWorkspace.class);
    }

    public CompletableFuture<Boolean> deleteWorkspace(
            String workspaceId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = workspaceParams(workspaceId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.WORKSPACE_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<WireThread> startThread(String workspaceId, String title) {
        return startThread(workspaceId, title, null);
    }

    public CompletableFuture<WireThread> startThread(String workspaceId, String title, String idempotencyKey) {
        ObjectNode params = object();
        params.put("workspaceId", workspaceId);
        params.put("title", title == null ? "" : title);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.THREAD_START, params, WireThread.class);
    }

    public CompletableFuture<ResumeResult> resumeThread(String threadId, long afterSequence) {
        ObjectNode params = threadParams(threadId);
        params.put("afterSequence", afterSequence);
        return connection.request(RpcMethods.THREAD_RESUME, params).thenApply(node -> {
            WireThreadSnapshot snapshot = convert(node.path("snapshot"), WireThreadSnapshot.class);
            List<WireEvent> events = list(node.path("events"), WireEvent.class);
            List<JsonNode> liveItems = node.path("liveItems").isArray()
                    ? json.convertValue(
                            node.path("liveItems"),
                            json.getTypeFactory().constructCollectionType(List.class, JsonNode.class))
                    : List.of();
            return new ResumeResult(snapshot, events, liveItems);
        });
    }

    public CompletableFuture<WireThreadSnapshot> readThread(String threadId) {
        return request(RpcMethods.THREAD_READ, threadParams(threadId), WireThreadSnapshot.class);
    }

    public CompletableFuture<List<WireThread>> listThreads(boolean includeArchived) {
        ObjectNode params = object();
        params.put("includeArchived", includeArchived);
        return connection.request(RpcMethods.THREAD_LIST, params).thenApply(node -> list(node, WireThread.class));
    }

    public CompletableFuture<WireThread> forkThread(String threadId, String throughTurnId, String title) {
        return forkThread(threadId, throughTurnId, title, null);
    }

    public CompletableFuture<WireThread> forkThread(
            String threadId, String throughTurnId, String title, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("throughTurnId", throughTurnId);
        params.put("title", title == null ? "" : title);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.THREAD_FORK, params, WireThread.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireThreadExecutionSummary> threadExecutionSummary(String threadId) {
        return request(
                RpcMethods.THREAD_EXECUTION_SUMMARY,
                threadParams(threadId),
                com.javaclaw.protocol.WireThreadExecutionSummary.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireThreadBranchStart> retryInNewBranch(
            String threadId, String targetTurnId, String replacementText, String profileId, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("targetTurnId", targetTurnId);
        params.put("replaceInput", replacementText != null);
        if (replacementText != null) {
            params.put("replacementText", replacementText);
        }
        params.put("profileId", profileId);
        putIdempotencyKey(params, idempotencyKey);
        return request(
                RpcMethods.THREAD_RETRY_IN_NEW_BRANCH, params, com.javaclaw.protocol.WireThreadBranchStart.class);
    }

    public CompletableFuture<WireThread> updateThread(
            String threadId, String title, long expectedRevision, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("title", title == null ? "" : title);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.THREAD_UPDATE, params, WireThread.class);
    }

    public CompletableFuture<WireThread> archiveThread(String threadId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.THREAD_ARCHIVE, params, WireThread.class);
    }

    public CompletableFuture<WireThread> unarchiveThread(
            String threadId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.THREAD_UNARCHIVE, params, WireThread.class);
    }

    public CompletableFuture<Boolean> deleteThread(String threadId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = threadParams(threadId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.THREAD_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    CompletableFuture<Void> startCompaction(String threadId) {
        ObjectNode params = threadParams(threadId);
        return connection.request(RpcMethods.THREAD_COMPACT_START, params).thenApply(ignored -> null);
    }

    CompletableFuture<List<com.javaclaw.protocol.WireWorktree>> worktrees(String workspaceId) {
        return connection
                .request(RpcMethods.WORKTREE_LIST, object().put("workspaceId", workspaceId))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireWorktree.class));
    }

    CompletableFuture<com.javaclaw.protocol.WireWorktreePatch> worktreePatch(String child, long revision) {
        return request(
                RpcMethods.WORKTREE_PATCH,
                object().put("childThreadId", child).put("expectedRevision", revision),
                com.javaclaw.protocol.WireWorktreePatch.class);
    }

    CompletableFuture<com.javaclaw.protocol.WireWorktree> cleanupWorktree(
            String child, long revision, boolean discard, String key) {
        ObjectNode params = object().put("childThreadId", child)
                .put("expectedRevision", revision)
                .put("discardUnmerged", discard);
        putIdempotencyKey(params, key);
        return request(RpcMethods.WORKTREE_CLEANUP, params, com.javaclaw.protocol.WireWorktree.class);
    }

    CompletableFuture<WireTurn> startTurn(
            String threadId,
            List<? extends JsonNode> input,
            String profileId,
            JsonNode restrictions,
            String idempotencyKey) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        ObjectNode params = threadParams(threadId);
        ArrayNode values = params.putArray("input");
        input.forEach(value -> values.add(Objects.requireNonNull(value, "input item")));
        params.put("profileId", profileId);
        if (restrictions != null && restrictions.isObject() && !restrictions.isEmpty()) {
            params.set("config", restrictions);
        }
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.TURN_START, params, WireTurn.class);
    }

    public CompletableFuture<List<WireProfile>> listProfiles() {
        return connection.request(RpcMethods.PROFILE_LIST, object()).thenApply(node -> list(node, WireProfile.class));
    }

    public CompletableFuture<WireProfile> readProfile(String profileId) {
        ObjectNode params = object();
        params.put("profileId", profileId);
        return request(RpcMethods.PROFILE_READ, params, WireProfile.class);
    }

    public CompletableFuture<WireProfile> putProfile(
            WireProfile profile, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(profile, "profile");
        ObjectNode params = object();
        params.set("profile", json.valueToTree(profile));
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.PROFILE_PUT, params, WireProfile.class);
    }

    public CompletableFuture<Boolean> deleteProfile(String profileId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("profileId", profileId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.PROFILE_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<List<WireProvider>> listProviders() {
        return connection.request(RpcMethods.PROVIDER_LIST, object()).thenApply(node -> list(node, WireProvider.class));
    }

    public CompletableFuture<WireProvider> configureProvider(
            String provider, Map<String, String> config, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("provider", provider);
        params.set("config", json.valueToTree(Map.copyOf(config)));
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.PROVIDER_CONFIGURE, params, WireProvider.class);
    }

    public CompletableFuture<WireSecretMetadata> setProviderCredential(
            String provider, char[] credential, String idempotencyKey) {
        char[] copy = Objects.requireNonNull(credential, "credential").clone();
        try {
            ObjectNode params = object();
            params.put("provider", provider);
            params.put("credential", new String(copy));
            putIdempotencyKey(params, idempotencyKey);
            return request(RpcMethods.PROVIDER_CREDENTIAL_SET, params, WireSecretMetadata.class);
        } finally {
            java.util.Arrays.fill(copy, '\0');
        }
    }

    public CompletableFuture<Boolean> clearProviderCredential(
            String provider, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("provider", provider);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.PROVIDER_CREDENTIAL_CLEAR, params)
                .thenApply(node -> node.path("cleared").asBoolean());
    }

    public CompletableFuture<List<WirePlugin>> listPlugins() {
        return connection.request(RpcMethods.PLUGIN_LIST, object()).thenApply(node -> list(node, WirePlugin.class));
    }

    public CompletableFuture<WirePlugin> readPlugin(String pluginId) {
        return request(RpcMethods.PLUGIN_READ, idParams("pluginId", pluginId), WirePlugin.class);
    }

    public CompletableFuture<WirePlugin> installPlugin(
            String sha256,
            boolean sourceConfirmed,
            boolean permissionsApproved,
            boolean enabled,
            String idempotencyKey) {
        ObjectNode params = object();
        params.put("sha256", Objects.requireNonNull(sha256, "sha256"));
        params.put("sourceConfirmed", sourceConfirmed);
        params.put("permissionsApproved", permissionsApproved);
        params.put("enabled", enabled);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.PLUGIN_INSTALL, params, WirePlugin.class);
    }

    public CompletableFuture<WirePlugin> setPluginEnabled(
            String pluginId, boolean enabled, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("pluginId", pluginId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(enabled ? RpcMethods.PLUGIN_ENABLE : RpcMethods.PLUGIN_DISABLE, params, WirePlugin.class);
    }

    public CompletableFuture<Boolean> uninstallPlugin(String pluginId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("pluginId", pluginId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.PLUGIN_UNINSTALL, params)
                .thenApply(node -> node.path("removed").asBoolean());
    }

    public CompletableFuture<WirePlugin> pluginHealth(String pluginId) {
        return request(RpcMethods.PLUGIN_HEALTH, idParams("pluginId", pluginId), WirePlugin.class);
    }

    public CompletableFuture<List<WirePluginTrustKey>> listPluginTrust() {
        return connection
                .request(RpcMethods.PLUGIN_TRUST_LIST, object())
                .thenApply(node -> list(node, WirePluginTrustKey.class));
    }

    public CompletableFuture<WirePluginTrustKey> addPluginTrust(
            String keyId, byte[] x509PublicKey, String label, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("keyId", keyId);
        params.put(
                "publicKey",
                Base64.getEncoder().encodeToString(Objects.requireNonNull(x509PublicKey, "x509PublicKey")));
        params.put("label", label);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.PLUGIN_TRUST_ADD, params, WirePluginTrustKey.class);
    }

    public CompletableFuture<Boolean> removePluginTrust(String keyId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("keyId", keyId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.PLUGIN_TRUST_REMOVE, params)
                .thenApply(node -> node.path("removed").asBoolean());
    }

    public CompletableFuture<List<WireMcpServer>> listMcpServers() {
        return connection.request(RpcMethods.MCP_LIST, object()).thenApply(node -> list(node, WireMcpServer.class));
    }

    public CompletableFuture<WireMcpServer> configureMcpServer(
            String mcpId,
            String pluginId,
            String name,
            JsonNode config,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey) {
        ObjectNode params = object();
        params.put("mcpId", mcpId);
        if (pluginId != null && !pluginId.isBlank()) {
            params.put("pluginId", pluginId);
        }
        params.put("name", name);
        params.set("config", Objects.requireNonNull(config, "config"));
        params.put("enabled", enabled);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.MCP_CONFIGURE, params, WireMcpServer.class);
    }

    public CompletableFuture<WireMcpServer> mcpHealth(String mcpId) {
        return request(RpcMethods.MCP_HEALTH, idParams("mcpId", mcpId), WireMcpServer.class);
    }

    CompletableFuture<JsonNode> discoverMcp(String mcpId) {
        return connection.request(RpcMethods.MCP_DISCOVER, idParams("mcpId", mcpId));
    }

    CompletableFuture<WireTurn> adoptPlan(
            String threadId, String itemId, String profileId, long profileRevision, String decisions, String key) {
        var params = idParams("threadId", threadId);
        params.put("planItemId", itemId)
                .put("profileId", profileId)
                .put("expectedProfileRevision", profileRevision)
                .put("decisions", decisions);
        putIdempotencyKey(params, key);
        return request(RpcMethods.THREAD_PLAN_ADOPT, params, WireTurn.class);
    }

    CompletableFuture<com.javaclaw.protocol.WirePluginPreview> previewPlugin(String sha256) {
        return request(
                RpcMethods.PLUGIN_PREVIEW,
                idParams("attachmentSha256", sha256),
                com.javaclaw.protocol.WirePluginPreview.class);
    }

    CompletableFuture<JsonNode> startMcpAuthorization(String mcpId) {
        return connection.request(RpcMethods.MCP_AUTHORIZE_START, idParams("mcpId", mcpId));
    }

    CompletableFuture<Boolean> cancelMcpAuthorization(String authorizationId) {
        return connection
                .request(RpcMethods.MCP_AUTHORIZE_CANCEL, idParams("authorizationId", authorizationId))
                .thenApply(node -> node.path("cancelled").asBoolean());
    }

    CompletableFuture<WireSecretMetadata> setMcpCredential(String mcpId, char[] credential, String idempotencyKey) {
        char[] copy = Objects.requireNonNull(credential, "credential").clone();
        try {
            ObjectNode params = idParams("mcpId", mcpId);
            params.put("credential", new String(copy));
            putIdempotencyKey(params, idempotencyKey);
            return request(RpcMethods.MCP_CREDENTIAL_SET, params, WireSecretMetadata.class);
        } finally {
            java.util.Arrays.fill(copy, '\0');
        }
    }

    CompletableFuture<WireSecretMetadata> readMcpCredential(String mcpId) {
        return request(RpcMethods.MCP_CREDENTIAL_READ, idParams("mcpId", mcpId), WireSecretMetadata.class);
    }

    CompletableFuture<Boolean> clearMcpCredential(String mcpId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("mcpId", mcpId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.MCP_CREDENTIAL_CLEAR, params)
                .thenApply(node -> node.path("cleared").asBoolean());
    }

    public CompletableFuture<List<WireAutomation>> listAutomations() {
        return connection
                .request(RpcMethods.AUTOMATION_LIST, object())
                .thenApply(node -> list(node, WireAutomation.class));
    }

    public CompletableFuture<WireAutomation> readAutomation(String automationId) {
        return request(RpcMethods.AUTOMATION_READ, idParams("automationId", automationId), WireAutomation.class);
    }

    public CompletableFuture<WireAutomation> putAutomation(
            WireAutomation automation, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(automation, "automation");
        ObjectNode params = object();
        params.set("automation", json.valueToTree(automation));
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.AUTOMATION_PUT, params, WireAutomation.class);
    }

    public CompletableFuture<Boolean> deleteAutomation(
            String automationId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("automationId", automationId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.AUTOMATION_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<WireTurn> startAutomation(String automationId, String idempotencyKey) {
        ObjectNode params = idParams("automationId", automationId);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.AUTOMATION_START, params, WireTurn.class);
    }

    public CompletableFuture<Boolean> interruptAutomation(String automationId) {
        return connection
                .request(RpcMethods.AUTOMATION_INTERRUPT, idParams("automationId", automationId))
                .thenApply(node -> node.path("interrupted").asBoolean());
    }

    public CompletableFuture<WireTurn> resumeAutomation(String automationId, String idempotencyKey) {
        ObjectNode params = idParams("automationId", automationId);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.AUTOMATION_RESUME, params, WireTurn.class);
    }

    public CompletableFuture<List<WireItem>> automationItems(String automationId) {
        return connection
                .request(RpcMethods.AUTOMATION_ITEMS, idParams("automationId", automationId))
                .thenApply(node -> list(node, WireItem.class));
    }

    public CompletableFuture<List<WireSchedule>> listSchedules() {
        return connection.request(RpcMethods.SCHEDULE_LIST, object()).thenApply(node -> list(node, WireSchedule.class));
    }

    public CompletableFuture<WireSchedule> readSchedule(String scheduleId) {
        return request(RpcMethods.SCHEDULE_READ, idParams("scheduleId", scheduleId), WireSchedule.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireSchedulePreview> previewSchedule(
            String cronExpression, String zoneId, int count) {
        ObjectNode params = object();
        params.put("cronExpression", cronExpression);
        params.put("zoneId", zoneId);
        params.put("count", count);
        return request(RpcMethods.SCHEDULE_PREVIEW, params, com.javaclaw.protocol.WireSchedulePreview.class);
    }

    public CompletableFuture<WireSchedule> putSchedule(
            WireSchedule schedule, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(schedule, "schedule");
        ObjectNode params = object();
        params.set("schedule", json.valueToTree(schedule));
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.SCHEDULE_PUT, params, WireSchedule.class);
    }

    public CompletableFuture<WireSchedule> setScheduleEnabled(
            String scheduleId, boolean enabled, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("scheduleId", scheduleId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(enabled ? RpcMethods.SCHEDULE_ENABLE : RpcMethods.SCHEDULE_DISABLE, params, WireSchedule.class);
    }

    public CompletableFuture<Boolean> deleteSchedule(String scheduleId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = idParams("scheduleId", scheduleId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.SCHEDULE_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<ScheduleTriggerResult> triggerSchedule(String scheduleId, String idempotencyKey) {
        ObjectNode params = idParams("scheduleId", scheduleId);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.SCHEDULE_TRIGGER, params)
                .thenApply(node -> new ScheduleTriggerResult(
                        node.path("skipped").asBoolean(),
                        node.path("reason").asText(),
                        node.has("turn") ? convert(node.path("turn"), WireTurn.class) : null));
    }

    public CompletableFuture<List<WireMemory>> listMemories(String workspaceId) {
        return connection
                .request(RpcMethods.MEMORY_LIST, workspaceParams(workspaceId))
                .thenApply(node -> list(node, WireMemory.class));
    }

    public CompletableFuture<com.javaclaw.protocol.WireMemoryDetail> readMemory(String id) {
        return request(RpcMethods.MEMORY_READ, idParams("memoryId", id), com.javaclaw.protocol.WireMemoryDetail.class);
    }

    public CompletableFuture<List<com.javaclaw.protocol.WireMemoryDetail>> memoryHistory(String id) {
        return connection
                .request(RpcMethods.MEMORY_HISTORY, idParams("memoryId", id))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireMemoryDetail.class));
    }

    public CompletableFuture<com.javaclaw.protocol.WireMemoryDetail> saveMemory(
            com.javaclaw.protocol.WireMemoryDetail memory, long revision, String key) {
        ObjectNode params = object();
        params.set("memory", json.valueToTree(memory));
        params.put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return request(RpcMethods.MEMORY_SAVE, params, com.javaclaw.protocol.WireMemoryDetail.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireMemoryDetail> restoreMemory(
            String id, long sourceRevision, long revision, String key) {
        ObjectNode params = idParams("memoryId", id);
        params.put("sourceRevision", sourceRevision).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return request(RpcMethods.MEMORY_RESTORE, params, com.javaclaw.protocol.WireMemoryDetail.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireMemoryProposal> proposeMemory(
            com.javaclaw.protocol.WireMemoryDetail memory, long revision, String reason, String key) {
        ObjectNode params = object();
        params.set("memory", json.valueToTree(memory));
        params.put("expectedRevision", revision).put("reason", reason);
        putIdempotencyKey(params, key);
        return request(RpcMethods.MEMORY_PROPOSE, params, com.javaclaw.protocol.WireMemoryProposal.class);
    }

    public CompletableFuture<List<com.javaclaw.protocol.WireMemoryProposal>> memoryProposals(String workspace) {
        return connection
                .request(RpcMethods.MEMORY_PROPOSALS, idParams("workspaceId", workspace))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireMemoryProposal.class));
    }

    public CompletableFuture<com.javaclaw.protocol.WireMemoryProposal> reviewMemoryProposal(
            String id, boolean accept, long revision, String key) {
        ObjectNode params = idParams("proposalId", id);
        params.put("accept", accept).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return request(RpcMethods.MEMORY_REVIEW, params, com.javaclaw.protocol.WireMemoryProposal.class);
    }

    public CompletableFuture<WireMemory> putMemory(
            String memoryId,
            String workspaceId,
            String kind,
            String content,
            long expectedRevision,
            String idempotencyKey) {
        ObjectNode params = workspaceParams(workspaceId);
        if (memoryId != null && !memoryId.isBlank()) {
            params.put("memoryId", memoryId);
        }
        params.put("kind", kind);
        params.put("content", content);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.MEMORY_PUT, params, WireMemory.class);
    }

    public CompletableFuture<Boolean> deleteMemory(String memoryId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("memoryId", memoryId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.MEMORY_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<List<WireKnowledgeSource>> listKnowledgeSources(String workspaceId) {
        return connection
                .request(RpcMethods.KNOWLEDGE_SOURCE_LIST, workspaceParams(workspaceId))
                .thenApply(node -> list(node, WireKnowledgeSource.class));
    }

    public CompletableFuture<List<com.javaclaw.protocol.WireKnowledgeSourceStats>> knowledgeSourceStats(
            String workspaceId) {
        return connection
                .request(RpcMethods.KNOWLEDGE_SOURCE_STATS, workspaceParams(workspaceId))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireKnowledgeSourceStats.class));
    }

    public CompletableFuture<WireKnowledgeSource> readKnowledgeSource(String sourceId) {
        return request(RpcMethods.KNOWLEDGE_SOURCE_READ, sourceParams(sourceId), WireKnowledgeSource.class);
    }

    public CompletableFuture<WireKnowledgeSource> importKnowledgeSource(
            String workspaceId,
            String attachmentSha256,
            String attachmentMediaType,
            String displayName,
            String idempotencyKey) {
        ObjectNode params = workspaceParams(workspaceId);
        params.put("sha256", Objects.requireNonNull(attachmentSha256, "attachmentSha256"));
        params.put("mediaType", Objects.requireNonNull(attachmentMediaType, "attachmentMediaType"));
        params.put("displayName", displayName == null ? "knowledge" : displayName);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.KNOWLEDGE_SOURCE_IMPORT, params, WireKnowledgeSource.class);
    }

    public CompletableFuture<WireKnowledgeSource> reindexKnowledgeSource(
            String sourceId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = sourceParams(sourceId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.KNOWLEDGE_SOURCE_REINDEX, params, WireKnowledgeSource.class);
    }

    public CompletableFuture<Boolean> deleteKnowledgeSource(
            String sourceId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = sourceParams(sourceId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.KNOWLEDGE_SOURCE_DELETE, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<List<WireKnowledgeHit>> searchKnowledge(String workspaceId, String query, int limit) {
        ObjectNode params = workspaceParams(workspaceId);
        params.put("query", query);
        params.put("limit", limit);
        return connection
                .request(RpcMethods.KNOWLEDGE_SEARCH, params)
                .thenApply(node -> list(node, WireKnowledgeHit.class));
    }

    public CompletableFuture<List<WireSkill>> listSkills() {
        return connection.request(RpcMethods.SKILL_LIST, object()).thenApply(node -> list(node, WireSkill.class));
    }

    public CompletableFuture<WireSkill> readSkill(String id) {
        return request(RpcMethods.SKILL_READ, idParams("skillId", id), WireSkill.class);
    }

    public CompletableFuture<List<WireSkill>> skillHistory(String id) {
        return connection
                .request(RpcMethods.SKILL_HISTORY, idParams("skillId", id))
                .thenApply(node -> list(node, WireSkill.class));
    }

    public CompletableFuture<WireSkill> restoreSkill(String id, long sourceRevision, long revision, String key) {
        var params = idParams("skillId", id);
        params.put("sourceRevision", sourceRevision);
        params.put("expectedRevision", revision);
        params.put("idempotencyKey", key);
        return request(RpcMethods.SKILL_RESTORE, params, WireSkill.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireSkillResource> skillResource(
            String id, long revision, String path) {
        var params = idParams("skillId", id);
        params.put("revision", revision);
        params.put("path", path);
        return request(RpcMethods.SKILL_RESOURCE_READ, params, com.javaclaw.protocol.WireSkillResource.class);
    }

    public CompletableFuture<List<com.javaclaw.protocol.WireKnowledgeGeneration>> sourceHistory(String id) {
        return connection
                .request(RpcMethods.KNOWLEDGE_SOURCE_HISTORY, idParams("sourceId", id))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireKnowledgeGeneration.class));
    }

    public CompletableFuture<String> sourceContent(String id, long revision) {
        var params = idParams("sourceId", id);
        params.put("revision", revision);
        return connection.request(RpcMethods.KNOWLEDGE_SOURCE_CONTENT, params).thenApply(JsonNode::asText);
    }

    public CompletableFuture<com.javaclaw.protocol.WireLearningSettings> learningSettings(String workspace) {
        return request(
                RpcMethods.LEARNING_READ,
                idParams("workspaceId", workspace),
                com.javaclaw.protocol.WireLearningSettings.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireLearningSettings> saveLearningSettings(
            String workspace, String mode, boolean memoryAutomatic, long revision, String key) {
        var params = idParams("workspaceId", workspace);
        params.put("skillMode", mode);
        params.put("memoryAutomatic", memoryAutomatic);
        params.put("expectedRevision", revision);
        params.put("idempotencyKey", key);
        return request(RpcMethods.LEARNING_CONFIGURE, params, com.javaclaw.protocol.WireLearningSettings.class);
    }

    public CompletableFuture<List<com.javaclaw.protocol.WireSkillProposal>> skillProposals(String workspace) {
        return connection
                .request(RpcMethods.SKILL_PROPOSALS, idParams("workspaceId", workspace))
                .thenApply(node -> list(node, com.javaclaw.protocol.WireSkillProposal.class));
    }

    public CompletableFuture<com.javaclaw.protocol.WireSkillProposal> proposeSkill(
            JsonNode draft, String reason, String key) {
        var params = object();
        params.set("draft", draft);
        params.put("reason", reason);
        params.put("idempotencyKey", key);
        return request(RpcMethods.SKILL_PROPOSE, params, com.javaclaw.protocol.WireSkillProposal.class);
    }

    public CompletableFuture<com.javaclaw.protocol.WireSkillProposal> reviewSkillProposal(
            String id, boolean accept, long revision, String key) {
        var params = idParams("proposalId", id);
        params.put("accept", accept);
        params.put("expectedRevision", revision);
        params.put("idempotencyKey", key);
        return request(RpcMethods.SKILL_REVIEW, params, com.javaclaw.protocol.WireSkillProposal.class);
    }

    public CompletableFuture<WireSkill> installSkill(
            String skillId,
            String name,
            String version,
            JsonNode manifest,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey) {
        ObjectNode params = object();
        params.put("skillId", skillId);
        params.put("name", name);
        params.put("version", version);
        params.set("manifest", Objects.requireNonNull(manifest, "manifest"));
        params.put("enabled", enabled);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.SKILL_INSTALL, params, WireSkill.class);
    }

    public CompletableFuture<Boolean> setSkillEnabled(
            String skillId, boolean enabled, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("skillId", skillId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(enabled ? RpcMethods.SKILL_ENABLE : RpcMethods.SKILL_DISABLE, params)
                .thenApply(node -> node.path("updated").asBoolean());
    }

    public CompletableFuture<Boolean> uninstallSkill(String skillId, long expectedRevision, String idempotencyKey) {
        ObjectNode params = object();
        params.put("skillId", skillId);
        params.put("expectedRevision", expectedRevision);
        putIdempotencyKey(params, idempotencyKey);
        return connection
                .request(RpcMethods.SKILL_UNINSTALL, params)
                .thenApply(node -> node.path("deleted").asBoolean());
    }

    public CompletableFuture<WireAttachmentUpload> startAttachmentUpload(
            String sha256, String mediaType, String displayName, long sizeBytes, String idempotencyKey) {
        if (sizeBytes < 0 || sizeBytes > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("attachment size must be between 0 and 256 MiB");
        }
        ObjectNode params = object();
        params.put("sha256", requiredSha256(sha256));
        params.put("mediaType", mediaType == null ? "application/octet-stream" : mediaType);
        params.put("displayName", displayName == null ? "attachment" : displayName);
        params.put("sizeBytes", sizeBytes);
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.ATTACHMENT_UPLOAD_START, params, WireAttachmentUpload.class);
    }

    public CompletableFuture<WireAttachmentUpload> appendAttachmentChunk(String uploadId, long offset, byte[] data) {
        byte[] value = Objects.requireNonNull(data, "data").clone();
        if (value.length < 1 || value.length > ATTACHMENT_CHUNK_BYTES) {
            throw new IllegalArgumentException("attachment chunk must contain 1-1048576 bytes");
        }
        ObjectNode params = object();
        params.put("uploadId", uploadId);
        params.put("offset", offset);
        params.put("data", Base64.getEncoder().encodeToString(value));
        return request(RpcMethods.ATTACHMENT_UPLOAD_CHUNK, params, WireAttachmentUpload.class);
    }

    public CompletableFuture<WireAttachment> completeAttachmentUpload(String uploadId) {
        ObjectNode params = object();
        params.put("uploadId", uploadId);
        return request(RpcMethods.ATTACHMENT_UPLOAD_COMPLETE, params, WireAttachment.class);
    }

    /** Reads and uploads a local SDK-side file; no local path crosses the protocol boundary. */
    public CompletableFuture<WireAttachment> uploadAttachment(Path file, String mediaType, String idempotencyKey) {
        Path path = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        return CompletableFuture.supplyAsync(
                () -> uploadBlocking(path, mediaType, idempotencyKey), command -> Thread.startVirtualThread(command));
    }

    public CompletableFuture<AttachmentChunk> readAttachment(String sha256, long offset, int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > ATTACHMENT_CHUNK_BYTES) {
            throw new IllegalArgumentException("maximumBytes must be between 1 and 1048576");
        }
        ObjectNode params = object();
        params.put("sha256", requiredSha256(sha256));
        params.put("offset", offset);
        params.put("maximumBytes", maximumBytes);
        return connection
                .request(RpcMethods.ATTACHMENT_READ, params)
                .thenApply(node -> new AttachmentChunk(
                        convert(node.path("attachment"), WireAttachment.class),
                        node.path("offset").asLong(),
                        Base64.getDecoder().decode(node.path("data").asText()),
                        node.path("eof").asBoolean()));
    }

    public CompletableFuture<Boolean> releaseAttachment(String sha256) {
        ObjectNode params = object();
        params.put("sha256", requiredSha256(sha256));
        return connection
                .request(RpcMethods.ATTACHMENT_RELEASE, params)
                .thenApply(node -> node.path("removed").asBoolean());
    }

    public CompletableFuture<Boolean> steerText(String turnId, String text) {
        ObjectNode params = object();
        params.put("turnId", turnId);
        ObjectNode input = params.putObject("input");
        input.put("type", "text");
        input.put("text", text);
        return connection
                .request(RpcMethods.TURN_STEER, params)
                .thenApply(node -> node.path("accepted").asBoolean());
    }

    public CompletableFuture<Boolean> interrupt(String turnId) {
        ObjectNode params = object();
        params.put("turnId", turnId);
        return connection
                .request(RpcMethods.TURN_INTERRUPT, params)
                .thenApply(node -> node.path("interrupted").asBoolean());
    }

    public CompletableFuture<Boolean> respondToApproval(String approvalId, boolean approved) {
        ObjectNode params = object();
        params.put("approvalId", approvalId);
        params.put("approved", approved);
        return connection
                .request(RpcMethods.APPROVAL_RESPOND, params)
                .thenApply(node -> node.path("accepted").asBoolean());
    }

    public CompletableFuture<Boolean> respondToUserInput(String requestId, String value, boolean cancelled) {
        ObjectNode params = object();
        params.put("requestId", requestId);
        params.put("value", value == null ? "" : value);
        params.put("cancelled", cancelled);
        return connection
                .request(RpcMethods.USER_INPUT_RESPOND, params)
                .thenApply(node -> node.path("accepted").asBoolean());
    }

    public CompletableFuture<List<WireEvent>> events(String threadId, long afterSequence, int limit) {
        ObjectNode params = threadParams(threadId);
        params.put("afterSequence", afterSequence);
        params.put("limit", limit);
        return connection.request(RpcMethods.EVENT_LIST, params).thenApply(node -> list(node, WireEvent.class));
    }

    public CompletableFuture<JsonNode> readConfiguration() {
        return connection.request(RpcMethods.CONFIG_READ, object());
    }

    public CompletableFuture<JsonNode> readDiagnostics(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("diagnostic limit must be 1-10000");
        }
        ObjectNode params = object();
        params.put("limit", limit);
        return connection.request(RpcMethods.DIAGNOSTICS_READ, params);
    }

    public CompletableFuture<WireAttachment> exportDiagnostics(String idempotencyKey) {
        ObjectNode params = object();
        putIdempotencyKey(params, idempotencyKey);
        return request(RpcMethods.DIAGNOSTICS_EXPORT, params, WireAttachment.class);
    }

    /** Applies a merge patch. A JSON null value removes that top-level key. */
    public CompletableFuture<JsonNode> updateConfiguration(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("configuration patch must be an object");
        }
        return connection.request(RpcMethods.CONFIG_UPDATE, patch);
    }

    public AutoCloseable onNotification(Consumer<ServerNotification> listener) {
        return connection.onNotification(listener);
    }

    public AutoCloseable onConnectionState(Consumer<ConnectionStatus> listener) {
        return connection.onConnectionState(listener);
    }

    public AutoCloseable onRecoveredThread(Consumer<ProtocolRecovery> listener) {
        return connection.onRecoveredThread(listener);
    }

    private <T> CompletableFuture<T> request(String method, JsonNode params, Class<T> type) {
        return connection.request(method, params).thenApply(node -> convert(node, type));
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid app server response for " + type.getSimpleName(), failure);
        }
    }

    private <T> List<T> list(JsonNode node, Class<T> elementType) {
        if (!node.isArray()) {
            throw new IllegalStateException("app server response is not an array");
        }
        return json.convertValue(node, json.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode threadParams(String threadId) {
        ObjectNode params = object();
        params.put("threadId", threadId);
        return params;
    }

    private static ObjectNode workspaceParams(String workspaceId) {
        ObjectNode params = object();
        params.put("workspaceId", Objects.requireNonNull(workspaceId, "workspaceId"));
        return params;
    }

    private static ObjectNode sourceParams(String sourceId) {
        ObjectNode params = object();
        params.put("sourceId", Objects.requireNonNull(sourceId, "sourceId"));
        return params;
    }

    private static ObjectNode idParams(String name, String value) {
        ObjectNode params = object();
        params.put(name, Objects.requireNonNull(value, name));
        return params;
    }

    private static void putIdempotencyKey(ObjectNode params, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            params.put("idempotencyKey", idempotencyKey);
        }
    }

    private WireAttachment uploadBlocking(Path path, String mediaType, String idempotencyKey) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("attachment is not a regular file: " + path);
            }
            long size = Files.size(path);
            if (size > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("attachment exceeds 256 MiB");
            }
            String sha256 = sha256(path);
            WireAttachmentUpload upload = startAttachmentUpload(
                            sha256, mediaType, path.getFileName().toString(), size, idempotencyKey)
                    .join();
            long offset = upload.receivedBytes();
            try (InputStream input = Files.newInputStream(path)) {
                input.skipNBytes(offset);
                byte[] buffer = new byte[ATTACHMENT_CHUNK_BYTES];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    byte[] chunk = count == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, count);
                    appendAttachmentChunk(upload.uploadId(), offset, chunk).join();
                    offset += count;
                }
            }
            return completeAttachmentUpload(upload.uploadId()).join();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot upload attachment", failure);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String requiredSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hex characters");
        }
        return value;
    }

    @Override
    public void close() {
        connection.close();
    }

    public record ResumeResult(WireThreadSnapshot snapshot, List<WireEvent> events, List<JsonNode> liveItems) {
        public ResumeResult {
            events = List.copyOf(events);
            liveItems = List.copyOf(liveItems);
        }
    }

    public record AttachmentChunk(WireAttachment attachment, long offset, byte[] data, boolean eof) {
        public AttachmentChunk {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    public record ScheduleTriggerResult(boolean skipped, String reason, WireTurn turn) {}

    CompletableFuture<List<com.javaclaw.protocol.WireBrowserSite>> listSites(String workspaceId) {
        return connection
                .request(RpcMethods.SITE_LIST, workspaceParams(workspaceId))
                .thenApply(value -> list(value, com.javaclaw.protocol.WireBrowserSite.class));
    }

    CompletableFuture<com.javaclaw.protocol.WireBrowserSite> putSite(
            com.javaclaw.sdk.model.BrowserSiteInfo site, boolean confirmed, String key) {
        var params = workspaceParams(site.workspaceId())
                .put("siteId", site.id())
                .put("name", site.name())
                .put("origin", site.origin().toString())
                .put("enabled", site.enabled())
                .put("expectedRevision", site.revision())
                .put("confirmed", confirmed);
        var origins = params.putArray("allowedOrigins");
        site.allowedOrigins().stream().map(java.net.URI::toString).sorted().forEach(origins::add);
        putIdempotencyKey(params, key);
        return request(RpcMethods.SITE_PUT, params, com.javaclaw.protocol.WireBrowserSite.class);
    }

    CompletableFuture<Boolean> disableSite(String id, long revision, String key) {
        var params = object().put("siteId", id).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return connection
                .request(RpcMethods.SITE_DELETE, params)
                .thenApply(value -> value.path("removed").asBoolean());
    }

    CompletableFuture<WireSecretMetadata> putSiteSecret(String id, String name, char[] value, String key) {
        var params = object().put("siteId", id).put("name", name).put("value", new String(value));
        putIdempotencyKey(params, key);
        return request(RpcMethods.SITE_CREDENTIAL_SET, params, WireSecretMetadata.class);
    }

    CompletableFuture<WireSecretMetadata> readSiteSecret(String id, String name, boolean state) {
        var params = object().put("siteId", id).put("name", name);
        return request(
                state ? RpcMethods.SITE_SESSION_READ : RpcMethods.SITE_CREDENTIAL_READ,
                params,
                WireSecretMetadata.class);
    }

    CompletableFuture<Boolean> clearSiteSecret(String id, String name, boolean state, long revision, String key) {
        var params = object().put("siteId", id).put("name", name).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return connection
                .request(state ? RpcMethods.SITE_SESSION_CLEAR : RpcMethods.SITE_CREDENTIAL_CLEAR, params)
                .thenApply(value -> value.path("cleared").asBoolean());
    }

    CompletableFuture<com.javaclaw.protocol.WireBrowserLogin> startBrowserLogin(
            String id, long revision, boolean confirmed, String key) {
        var params =
                object().put("siteId", id).put("expectedRevision", revision).put("confirmed", confirmed);
        putIdempotencyKey(params, key);
        return request(RpcMethods.SITE_LOGIN_START, params, com.javaclaw.protocol.WireBrowserLogin.class);
    }

    CompletableFuture<Boolean> finishBrowserLogin(String id, boolean save, String key) {
        var params = object().put("sessionId", id).put("save", save);
        putIdempotencyKey(params, key);
        return connection
                .request(RpcMethods.SITE_LOGIN_FINISH, params)
                .thenApply(value -> value.path("finished").asBoolean());
    }

    CompletableFuture<List<com.javaclaw.protocol.WireNetworkGrant>> listNetworkGrants(String workspaceId) {
        return connection
                .request(RpcMethods.NETWORK_GRANT_LIST, workspaceParams(workspaceId))
                .thenApply(value -> list(value, com.javaclaw.protocol.WireNetworkGrant.class));
    }

    CompletableFuture<com.javaclaw.protocol.WireNetworkGrant> putNetworkGrant(
            com.javaclaw.sdk.model.NetworkGrantInfo grant, boolean confirmed, String key) {
        var params = workspaceParams(grant.workspaceId())
                .put("grantId", grant.id())
                .put("purpose", grant.purpose())
                .put("origin", grant.origin().toString())
                .put("expiresAt", grant.expiresAt().toString())
                .put("enabled", grant.enabled())
                .put("expectedRevision", grant.revision())
                .put("confirmed", confirmed);
        var addresses = params.putArray("addresses");
        grant.addresses().stream().sorted().forEach(addresses::add);
        putIdempotencyKey(params, key);
        return request(RpcMethods.NETWORK_GRANT_PUT, params, com.javaclaw.protocol.WireNetworkGrant.class);
    }

    CompletableFuture<Boolean> disableNetworkGrant(String id, long revision, String key) {
        var params = object().put("grantId", id).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return connection
                .request(RpcMethods.NETWORK_GRANT_DELETE, params)
                .thenApply(value -> value.path("removed").asBoolean());
    }

    CompletableFuture<List<com.javaclaw.protocol.WireToolAuthorityOption>> toolAuthorizationOptions(
            String workspaceId) {
        return connection
                .request(RpcMethods.TOOL_AUTHORIZATION_OPTIONS, workspaceParams(workspaceId))
                .thenApply(value -> list(value, com.javaclaw.protocol.WireToolAuthorityOption.class));
    }

    CompletableFuture<List<com.javaclaw.protocol.WireToolAuthorization>> listToolAuthorizations(String workspaceId) {
        return connection
                .request(RpcMethods.TOOL_AUTHORIZATION_LIST, workspaceParams(workspaceId))
                .thenApply(value -> list(value, com.javaclaw.protocol.WireToolAuthorization.class));
    }

    CompletableFuture<com.javaclaw.protocol.WireToolAuthorization> putToolAuthorization(
            com.javaclaw.sdk.model.ToolAuthorizationInfo grant, boolean confirmed, String key) {
        var params = workspaceParams(grant.workspaceId())
                .put("authorizationId", grant.id())
                .put("sourceId", grant.sourceId())
                .put("toolName", grant.toolName())
                .put("sourceRevision", grant.sourceRevision())
                .put("schemaSha256", grant.schemaSha256())
                .put("argumentTemplate", grant.argumentTemplate().canonicalJson())
                .put("recipientField", grant.recipientField())
                .put("maximumUses", grant.maximumUses())
                .put("expiresAt", grant.expiresAt().toString())
                .put("enabled", grant.enabled())
                .put("expectedRevision", grant.revision())
                .put("confirmed", confirmed);
        var variables = params.putArray("variableFields");
        grant.variableFields().stream().sorted().forEach(variables::add);
        putIdempotencyKey(params, key);
        return request(RpcMethods.TOOL_AUTHORIZATION_PUT, params, com.javaclaw.protocol.WireToolAuthorization.class);
    }

    CompletableFuture<Boolean> disableToolAuthorization(String id, long revision, String key) {
        var params = object().put("authorizationId", id).put("expectedRevision", revision);
        putIdempotencyKey(params, key);
        return connection
                .request(RpcMethods.TOOL_AUTHORIZATION_DELETE, params)
                .thenApply(value -> value.path("removed").asBoolean());
    }
}
