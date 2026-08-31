package com.javaclaw.sdk;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.protocol.InitializeResult;
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
import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.AttachmentUploadInfo;
import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.ErrorItemContent;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.KnowledgeHitInfo;
import com.javaclaw.sdk.model.KnowledgeSourceInfo;
import com.javaclaw.sdk.model.McpServerInfo;
import com.javaclaw.sdk.model.MemoryInfo;
import com.javaclaw.sdk.model.PluginInfo;
import com.javaclaw.sdk.model.PluginTrustKeyInfo;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ProviderInfo;
import com.javaclaw.sdk.model.ScheduleInfo;
import com.javaclaw.sdk.model.SecretMetadata;
import com.javaclaw.sdk.model.ServerInfo;
import com.javaclaw.sdk.model.SkillInfo;
import com.javaclaw.sdk.model.StructuredItemContent;
import com.javaclaw.sdk.model.TextItemContent;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.TurnStartRequest;
import com.javaclaw.sdk.model.UnknownItemContent;
import com.javaclaw.sdk.model.UserInputItemContent;
import com.javaclaw.sdk.model.WorkspaceInfo;

/** The only Wire/SDK mapping boundary. It is intentionally package-private. */
final class SdkProtocolMapper {
    com.javaclaw.sdk.model.WorktreeInfo worktree(com.javaclaw.protocol.WireWorktree value) {
        return new com.javaclaw.sdk.model.WorktreeInfo(
                value.id(),
                value.workspaceId(),
                value.parentThreadId(),
                value.childThreadId(),
                value.state(),
                value.revision(),
                value.running(),
                value.backupSha256(),
                value.details(),
                Instant.parse(value.updatedAt()));
    }

    com.javaclaw.sdk.model.WorktreePatchInfo worktreePatch(com.javaclaw.protocol.WireWorktreePatch value) {
        return new com.javaclaw.sdk.model.WorktreePatchInfo(
                value.status(), value.patchAttachmentSha256(), value.conflicts(), value.message());
    }

    private static final Set<String> KNOWN_STRUCTURED_KINDS = Set.of(
            "plan",
            "commandExecution",
            "fileChange",
            "mcpToolCall",
            "dynamicToolCall",
            "approvalRequest",
            "userInputRequest",
            "userInputResponse",
            "subagentCall",
            "webSearch",
            "imageView",
            "contextUsage",
            "contextCompaction",
            "error");
    private final ObjectMapper json;

    SdkProtocolMapper(ObjectMapper json) {
        this.json = json;
    }

    com.javaclaw.sdk.model.AgentsInstructionResolutionInfo instructions(
            com.javaclaw.protocol.WireAgentsInstructionResolution value) {
        return new com.javaclaw.sdk.model.AgentsInstructionResolutionInfo(
                java.nio.file.Path.of(value.workingDirectory()),
                value.sources().stream()
                        .map(source -> new com.javaclaw.sdk.model.AgentsInstructionSourceInfo(
                                source.scope(),
                                java.nio.file.Path.of(source.path()),
                                source.bytes(),
                                source.sha256(),
                                source.truncated()))
                        .toList(),
                value.warnings(),
                value.totalProjectBytes());
    }

    JsonNode desktopTheme(String themeId) {
        return json.createObjectNode().put("desktop.theme", themeId);
    }

    ServerInfo server(InitializeResult value) {
        return new ServerInfo(
                value.protocolVersion(),
                value.serverName(),
                value.serverVersion(),
                value.capabilities(),
                value.connectionId());
    }

    WorkspaceInfo workspace(WireWorkspace value) {
        return new WorkspaceInfo(
                value.id(),
                value.name(),
                Path.of(value.root()),
                value.revision(),
                value.locked(),
                value.lockReason(),
                value.createdAt(),
                value.updatedAt());
    }

    ThreadInfo thread(WireThread value) {
        return new ThreadInfo(
                value.id(),
                value.workspaceId(),
                value.parentThreadId(),
                value.forkedFromTurnId(),
                value.title(),
                value.status(),
                value.baseSequence(),
                value.lastSequence(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    TurnInfo turn(WireTurn value) {
        if (value == null) {
            return null;
        }
        return new TurnInfo(
                value.id(),
                value.threadId(),
                value.status(),
                value.attemptId(),
                value.input().stream().map(this::document).toList(),
                document(value.config()),
                value.error(),
                value.startedAt(),
                value.completedAt());
    }

    com.javaclaw.sdk.model.ThreadExecutionSummaryInfo executionSummary(
            com.javaclaw.protocol.WireThreadExecutionSummary value) {
        return new com.javaclaw.sdk.model.ThreadExecutionSummaryInfo(
                value.threadId(),
                value.turns().stream()
                        .map(turn -> new com.javaclaw.sdk.model.TurnExecutionSummaryInfo(
                                turn.turnId(),
                                turn.profileId(),
                                turn.provider(),
                                turn.model(),
                                turn.status(),
                                turn.startedAt(),
                                turn.completedAt(),
                                turn.inputTokens(),
                                turn.outputTokens(),
                                turn.reasoningTokens()))
                        .toList());
    }

    com.javaclaw.sdk.model.ThreadBranchStartInfo branchStart(com.javaclaw.protocol.WireThreadBranchStart value) {
        return new com.javaclaw.sdk.model.ThreadBranchStartInfo(thread(value.thread()), turn(value.turn()));
    }

    com.javaclaw.sdk.model.KnowledgeSourceStatsInfo knowledgeStats(
            com.javaclaw.protocol.WireKnowledgeSourceStats value) {
        return new com.javaclaw.sdk.model.KnowledgeSourceStatsInfo(
                value.sourceId(),
                value.generation(),
                value.chunkCount(),
                value.retrievalMode(),
                value.indexedAt(),
                value.failureSummary());
    }

    com.javaclaw.sdk.model.SchedulePreviewInfo schedulePreview(com.javaclaw.protocol.WireSchedulePreview value) {
        return new com.javaclaw.sdk.model.SchedulePreviewInfo(
                value.cronExpression(), value.zoneId(), value.fireTimes());
    }

    ItemInfo item(WireItem value) {
        return new ItemInfo(
                value.id(),
                value.threadId(),
                value.turnId(),
                value.ordinal(),
                value.state(),
                itemContent(value.kind(), value.payload()),
                value.createdAt(),
                value.updatedAt());
    }

    EventInfo event(WireEvent value) {
        return new EventInfo(
                value.eventId(),
                value.threadId(),
                value.turnId(),
                value.sequence(),
                value.type(),
                value.schemaVersion(),
                value.correlationId(),
                value.causationId(),
                document(value.payload()),
                value.timestamp());
    }

    ThreadSnapshot snapshot(WireThreadSnapshot value) {
        return new ThreadSnapshot(
                thread(value.thread()),
                value.turns().stream().map(this::turn).toList(),
                value.items().stream().map(this::item).toList());
    }

    AttachmentInfo attachment(WireAttachment value) {
        return new AttachmentInfo(value.sha256(), value.mediaType(), value.sizeBytes(), value.referenceCount());
    }

    AttachmentUploadInfo upload(WireAttachmentUpload value) {
        return new AttachmentUploadInfo(
                value.uploadId(),
                value.expectedSha256(),
                value.mediaType(),
                value.displayName(),
                value.expectedSize(),
                value.receivedBytes(),
                value.chunkSize(),
                value.expiresAt());
    }

    ProfileInfo profile(WireProfile value) {
        return new ProfileInfo(
                value.id(),
                value.name(),
                value.kind(),
                value.provider(),
                value.model(),
                value.systemPrompt(),
                value.enabledTools(),
                value.requestedSandboxMode(),
                value.maxIterations(),
                value.maxModelCalls(),
                value.attributes(),
                value.revision(),
                value.updatedAt());
    }

    WireProfile profile(ProfileInfo value) {
        return new WireProfile(
                value.id(),
                value.name(),
                value.kind(),
                value.provider(),
                value.model(),
                value.systemPrompt(),
                value.enabledTools(),
                value.requestedSandboxMode(),
                value.maxIterations(),
                value.maxModelCalls(),
                value.attributes(),
                value.revision(),
                value.updatedAt());
    }

    ProviderInfo provider(WireProvider value) {
        return new ProviderInfo(
                value.id(),
                value.configured(),
                value.credentialRevision(),
                value.configRevision(),
                value.model(),
                value.embeddingModel(),
                value.baseUrl(),
                value.updatedAt());
    }

    SecretMetadata secret(WireSecretMetadata value) {
        return new SecretMetadata(
                value.namespace(), value.name(), value.configured(), value.revision(), value.updatedAt());
    }

    PluginInfo plugin(WirePlugin value) {
        return new PluginInfo(
                value.id(),
                value.version(),
                value.state(),
                value.enabled(),
                value.signatureVerified(),
                value.signerKeyId(),
                value.sourceConfirmed(),
                value.permissionsApproved(),
                value.restartCount(),
                value.lastError(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    PluginTrustKeyInfo trust(WirePluginTrustKey value) {
        return new PluginTrustKeyInfo(
                value.keyId(), value.label(), value.fingerprintSha256(), value.revision(), value.createdAt());
    }

    McpServerInfo mcp(WireMcpServer value) {
        return new McpServerInfo(
                value.id(),
                value.pluginId(),
                value.name(),
                document(value.config()),
                value.enabled(),
                value.state(),
                value.revision(),
                value.updatedAt());
    }

    AutomationInfo automation(WireAutomation value) {
        return new AutomationInfo(
                value.id(),
                value.kind(),
                value.name(),
                value.workspaceId(),
                value.profileId(),
                value.prompt(),
                document(value.definition()),
                value.status(),
                value.threadId(),
                value.activeTurnId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    WireAutomation automation(AutomationInfo value) {
        return new WireAutomation(
                value.id(),
                value.kind(),
                value.name(),
                value.workspaceId(),
                value.profileId(),
                value.prompt(),
                parse(value.definition()),
                value.status(),
                value.threadId(),
                value.activeTurnId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    ScheduleInfo schedule(WireSchedule value) {
        return new ScheduleInfo(
                value.id(),
                value.name(),
                value.workspaceId(),
                value.threadId(),
                value.profileId(),
                value.prompt(),
                value.cronExpression(),
                value.zoneId(),
                value.enabled(),
                value.nextFireAt(),
                value.lastFireAt(),
                value.lastResult(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    WireSchedule schedule(ScheduleInfo value) {
        return new WireSchedule(
                value.id(),
                value.name(),
                value.workspaceId(),
                value.threadId(),
                value.profileId(),
                value.prompt(),
                value.cronExpression(),
                value.zoneId(),
                value.enabled(),
                value.nextFireAt(),
                value.lastFireAt(),
                value.lastResult(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    MemoryInfo memory(WireMemory value) {
        return new MemoryInfo(
                value.id(),
                value.workspaceId(),
                value.kind(),
                value.content(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    com.javaclaw.sdk.model.MemoryDetailInfo memoryDetail(com.javaclaw.protocol.WireMemoryDetail value) {
        return new com.javaclaw.sdk.model.MemoryDetailInfo(
                value.id(),
                value.workspaceId(),
                value.kind(),
                value.subject(),
                value.attribute(),
                value.content(),
                value.pinned(),
                value.sourceItemIds(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    com.javaclaw.protocol.WireMemoryDetail memoryDetail(com.javaclaw.sdk.model.MemoryDetailInfo value) {
        return new com.javaclaw.protocol.WireMemoryDetail(
                value.id(),
                value.workspaceId(),
                value.kind(),
                value.subject(),
                value.attribute(),
                value.content(),
                value.pinned(),
                value.sourceItemIds(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    com.javaclaw.sdk.model.MemoryProposalInfo memoryProposal(com.javaclaw.protocol.WireMemoryProposal value) {
        return new com.javaclaw.sdk.model.MemoryProposalInfo(
                value.id(),
                memoryDetail(value.draft()),
                value.expectedTargetRevision(),
                value.state(),
                value.reason(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    KnowledgeSourceInfo source(WireKnowledgeSource value) {
        return new KnowledgeSourceInfo(
                value.id(),
                value.workspaceId(),
                value.attachmentSha256(),
                value.displayName(),
                value.mediaType(),
                value.status(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    KnowledgeHitInfo hit(WireKnowledgeHit value) {
        return new KnowledgeHitInfo(
                value.sourceId(),
                value.chunkId(),
                value.displayName(),
                value.content(),
                value.score(),
                value.sourceRevision());
    }

    com.javaclaw.sdk.model.LearningSettingsInfo learningSettings(com.javaclaw.protocol.WireLearningSettings value) {
        return new com.javaclaw.sdk.model.LearningSettingsInfo(
                value.workspaceId(), value.skillMode(), value.memoryAutomatic(), value.revision(), value.updatedAt());
    }

    com.javaclaw.sdk.model.SkillResourceInfo skillResource(com.javaclaw.protocol.WireSkillResource value) {
        return new com.javaclaw.sdk.model.SkillResourceInfo(
                value.path(), value.mediaType(), value.content(), value.executable());
    }

    com.javaclaw.sdk.model.KnowledgeGenerationInfo knowledgeGeneration(
            com.javaclaw.protocol.WireKnowledgeGeneration value) {
        return new com.javaclaw.sdk.model.KnowledgeGenerationInfo(
                value.sourceId(),
                value.revision(),
                value.contentSha256(),
                value.extractorFingerprint(),
                value.status(),
                value.createdAt());
    }

    com.javaclaw.sdk.model.SkillProposalInfo skillProposal(com.javaclaw.protocol.WireSkillProposal value) {
        return new com.javaclaw.sdk.model.SkillProposalInfo(
                value.id(),
                value.workspaceId(),
                value.targetId(),
                value.name(),
                value.version(),
                value.manifest(),
                value.sourceItemIds(),
                value.expectedTargetRevision(),
                value.state(),
                value.reason(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    com.fasterxml.jackson.databind.JsonNode skillDraft(com.javaclaw.sdk.model.SkillLearningDraft value) {
        var result = json.createObjectNode();
        result.put("workspaceId", value.workspaceId());
        result.put("targetId", value.targetId());
        result.put("name", value.name());
        result.put("version", value.version());
        result.put("manifest", value.manifest());
        result.put("expectedTargetRevision", value.expectedTargetRevision());
        var sources = result.putArray("sourceItemIds");
        value.sourceItemIds().forEach(sources::add);
        return result;
    }

    SkillInfo skill(WireSkill value) {
        return new SkillInfo(
                value.id(),
                value.name(),
                value.version(),
                document(value.manifest()),
                value.enabled(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    JsonNode parse(JsonDocument value) {
        try {
            return json.readTree(value == null ? "{}" : value.canonicalJson());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid SDK JSON document", failure);
        }
    }

    List<JsonNode> turnInputs(List<TurnInput> values) {
        ArrayList<JsonNode> result = new ArrayList<>();
        for (TurnInput value : values) {
            ObjectNode item = json.createObjectNode();
            if (value instanceof TurnInput.Text text) {
                item.put("type", "text");
                item.put("text", text.text());
            } else if (value instanceof TurnInput.Attachment attachment) {
                item.put("type", "attachment");
                item.put("sha256", attachment.sha256());
                item.put("mediaType", attachment.mediaType());
                item.put("displayName", attachment.displayName());
            } else {
                throw new IllegalArgumentException("unsupported Turn input " + value.getClass());
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    JsonNode turnRestrictions(TurnStartRequest value) {
        ObjectNode config = json.createObjectNode();
        switch (value.approvalMode()) {
            case PROFILE_DEFAULT -> {}
            case REQUIRE_ALL -> config.put("approvalPolicy", "ALWAYS");
            case DENY_ALL -> config.put("approvalPolicy", "NEVER");
        }
        switch (value.reasoningMode()) {
            case PROFILE_DEFAULT -> {}
            case SUMMARY_ONLY -> config.put("reasoningEffort", "low");
            case DISABLED -> config.put("reasoningEffort", "none");
        }
        return config;
    }

    JsonDocument document(JsonNode value) {
        JsonNode safe = value == null || value.isMissingNode() ? json.nullNode() : value;
        try {
            return new JsonDocument(json.writeValueAsString(sorted(safe)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize protocol JSON", impossible);
        }
    }

    ClientNotification notification(ServerNotification notification) {
        JsonNode params = notification.params() == null ? json.nullNode() : notification.params();
        String method = notification.method();
        if (RpcMethods.ITEM_DELTA.equals(method)) {
            return new ItemDeltaNotification(
                    params.path("threadId").asText(),
                    params.path("turnId").asText(),
                    params.path("itemId").asText(),
                    params.path("kind").asText(),
                    params.path("deltaSequence").asLong(),
                    params.path("delta").path("text").asText(""),
                    document(params.path("delta")),
                    instant(params.path("timestamp").asText(null)));
        }
        if (RpcMethods.RESYNC_REQUIRED.equals(method)) {
            return new ResyncRequiredNotification(
                    params.path("threadId").asText(),
                    params.path("afterSequence").asLong(),
                    params.path("reason").asText(""));
        }
        if (RpcMethods.APPROVAL_REQUESTED.equals(method)) {
            JsonNode payload = params.path("item").path("payload");
            return new ApprovalRequestedNotification(
                    threadId(params),
                    payload.path("approvalId").asText(),
                    payload.path("reason").asText(),
                    payload.path("risk").asText());
        }
        if (RpcMethods.USER_INPUT_REQUESTED.equals(method)) {
            JsonNode payload = params.path("item").path("payload");
            ArrayList<String> choices = new ArrayList<>();
            payload.path("choices").forEach(node -> choices.add(node.asText()));
            return new UserInputRequestedNotification(
                    threadId(params),
                    payload.path("requestId").asText(),
                    payload.path("prompt").asText(),
                    choices);
        }
        if (RpcMethods.MCP_AUTHORIZATION_REQUESTED.equals(method)) {
            return new McpAuthorizationRequestedNotification(
                    params.path("mcpId").asText(),
                    params.path("authorizationId").asText(),
                    URI.create(params.path("url").asText()),
                    instant(params.path("expiresAt").asText(null)));
        }
        if (RpcMethods.MCP_STATUS_CHANGED.equals(method)) {
            return new McpStatusChangedNotification(
                    params.path("mcpId").asText(),
                    params.path("state").asText(),
                    params.path("revision").asLong());
        }
        if (params.path("event").isObject()) {
            WireEvent event = convert(params.path("event"), WireEvent.class);
            WireItem item = params.path("item").isObject() ? convert(params.path("item"), WireItem.class) : null;
            WireTurn turn = params.path("turn").isObject() ? convert(params.path("turn"), WireTurn.class) : null;
            return new EventNotification(event(event), item == null ? null : item(item), turn(turn));
        }
        return new UnknownNotification(method, document(params));
    }

    RecoveredThread recovered(String threadId, JsonNode snapshot, JsonNode events, JsonNode liveItems) {
        ThreadSnapshot mappedSnapshot =
                snapshot != null && snapshot.isObject() ? snapshot(convert(snapshot, WireThreadSnapshot.class)) : null;
        List<EventInfo> mappedEvents = new ArrayList<>();
        if (events != null && events.isArray()) {
            events.forEach(value -> mappedEvents.add(event(convert(value, WireEvent.class))));
        }
        List<JsonDocument> mappedLive = new ArrayList<>();
        if (liveItems != null && liveItems.isArray()) {
            liveItems.forEach(value -> mappedLive.add(document(value)));
        }
        return new RecoveredThread(threadId, mappedSnapshot, mappedEvents, mappedLive);
    }

    private ItemContent itemContent(String kind, JsonNode payload) {
        JsonDocument value = document(payload);
        if ("artifact".equals(kind)) {
            return new com.javaclaw.sdk.model.ArtifactItemContent(
                    payload.path("artifactId").asText(""),
                    payload.path("category").asText(""),
                    payload.path("name").asText(""),
                    payload.path("revision").asLong(),
                    payload.path("content").asText(""),
                    stringValues(payload.path("sources")),
                    value);
        }
        if ("evaluation".equals(kind)) {
            return new com.javaclaw.sdk.model.EvaluationItemContent(
                    payload.path("scope").asText(""),
                    payload.path("passed").asBoolean(),
                    payload.path("summary").asText(""),
                    stringValues(payload.path("evidenceItemIds")),
                    stringValues(payload.path("remaining")),
                    value);
        }
        if ("checkpoint".equals(kind)) {
            return new com.javaclaw.sdk.model.CheckpointItemContent(
                    payload.path("executionId").asText(""),
                    payload.path("definitionHash").asText(""),
                    payload.path("stepId").asText(""),
                    payload.path("status").asText(""),
                    payload.path("iteration").asInt(),
                    payload.path("usedModelCalls").asInt(),
                    payload.path("usedTokens").asLong(),
                    payload.path("elapsedMillis").asLong(),
                    payload.path("summary").asText(""),
                    value);
        }
        if ("effectReceipt".equals(kind)) {
            return new com.javaclaw.sdk.model.EffectReceiptItemContent(
                    payload.path("key").asText(""),
                    payload.path("tool").asText(""),
                    payload.path("state").asText(""),
                    payload.path("summary").asText(""),
                    value);
        }
        if ("commandExecution".equals(kind)) {
            return new com.javaclaw.sdk.model.CommandItemContent(
                    stringValues(payload.path("argv")),
                    payload.path("exitCode").asInt(),
                    payload.path("stdout").asText(""),
                    payload.path("stderr").asText(""),
                    payload.path("timedOut").asBoolean(),
                    payload.path("truncated").asBoolean(),
                    value);
        }
        if ("fileChange".equals(kind)) {
            return new com.javaclaw.sdk.model.FileChangeItemContent(
                    payload.path("path").asText(""),
                    payload.path("change").asText(""),
                    payload.path("diff").asText(""),
                    value);
        }
        if ("mcpToolCall".equals(kind)) {
            return new com.javaclaw.sdk.model.McpItemContent(
                    payload.path("server").asText(""),
                    payload.path("tool").asText(""),
                    payload.path("result").path("status").asText(""),
                    payload.path("result").path("content").asText(""),
                    value);
        }
        if ("imageView".equals(kind)) {
            return new com.javaclaw.sdk.model.ImageItemContent(
                    payload.path("uri").asText(""), payload.path("description").asText(""), value);
        }
        if ("subagentCall".equals(kind)) {
            return new com.javaclaw.sdk.model.SubagentItemContent(
                    payload.path("childThreadId").asText(""),
                    payload.path("task").asText(""),
                    payload.path("summary").asText(""),
                    value);
        }
        if ("promptDraft".equals(kind)) {
            return new com.javaclaw.sdk.model.PromptDraftItemContent(
                    payload.path("profileId").asText(),
                    payload.path("expectedRevision").asLong(),
                    payload.path("draft").asText(),
                    stringValues(payload.path("changes")),
                    stringValues(payload.path("warnings")),
                    value);
        }
        if ("plan".equals(kind)) {
            var details = payload.path("details");
            var steps = new ArrayList<com.javaclaw.sdk.model.PlanItemContent.Step>();
            payload.path("steps")
                    .forEach(step -> steps.add(new com.javaclaw.sdk.model.PlanItemContent.Step(
                            step.path("step").asText(), step.path("status").asText())));
            return new com.javaclaw.sdk.model.PlanItemContent(
                    details.path("goal").asText(),
                    details.path("scope").asText(),
                    steps,
                    stringValues(details.path("dependencies")),
                    stringValues(details.path("acceptanceCriteria")),
                    stringValues(details.path("risks")),
                    stringValues(details.path("openQuestions")),
                    value);
        }
        if ("userMessage".equals(kind)) {
            var attachments = new ArrayList<com.javaclaw.sdk.model.TurnInput.Attachment>();
            payload.path("attachments")
                    .forEach(reference -> attachments.add(new com.javaclaw.sdk.model.TurnInput.Attachment(
                            reference.path("sha256").asText(),
                            reference.path("mediaType").asText(),
                            reference.path("displayName").asText())));
            return new com.javaclaw.sdk.model.UserMessageItemContent(
                    payload.path("text").asText(""), attachments, value);
        }
        if (Set.of("agentMessage", "reasoningSummary").contains(kind)) {
            return new TextItemContent(kind, payload.path("text").asText(""), value);
        }
        if ("error".equals(kind)) {
            return new ErrorItemContent(
                    kind,
                    payload.path("code").asText(),
                    payload.path("message").asText(),
                    payload.path("retryable").asBoolean(),
                    value);
        }
        if ("approvalRequest".equals(kind)) {
            return new ApprovalItemContent(
                    kind,
                    payload.path("approvalId").asText(),
                    payload.path("reason").asText(),
                    payload.path("risk").asText(),
                    value);
        }
        if ("userInputRequest".equals(kind)) {
            ArrayList<String> choices = new ArrayList<>();
            payload.path("choices").forEach(item -> choices.add(item.asText()));
            return new UserInputItemContent(
                    kind,
                    payload.path("requestId").asText(),
                    payload.path("prompt").asText(),
                    choices,
                    value);
        }
        return KNOWN_STRUCTURED_KINDS.contains(kind)
                ? new StructuredItemContent(kind, value)
                : new UnknownItemContent(kind, value);
    }

    private static List<String> stringValues(JsonNode values) {
        var result = new ArrayList<String>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return json.treeToValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid protocol " + type.getSimpleName(), failure);
        }
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = json.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            fields.forEach(object::set);
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = json.createArrayNode();
            value.forEach(element -> array.add(sorted(element)));
            return array;
        }
        return value.deepCopy();
    }

    private static String threadId(JsonNode params) {
        String direct = params.path("threadId").asText("");
        return direct.isBlank() ? params.path("event").path("threadId").asText("") : direct;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    com.javaclaw.sdk.model.ToolAuthorizationInfo toolAuthorization(com.javaclaw.protocol.WireToolAuthorization value) {
        return new com.javaclaw.sdk.model.ToolAuthorizationInfo(
                value.id(),
                value.workspaceId(),
                value.sourceId(),
                value.toolName(),
                value.sourceRevision(),
                value.schemaSha256(),
                new JsonDocument(value.argumentTemplate()),
                value.recipientField(),
                java.util.Set.copyOf(value.variableFields()),
                value.maximumUses(),
                value.consumedUses(),
                Instant.parse(value.expiresAt()),
                value.enabled(),
                value.revision(),
                Instant.parse(value.updatedAt()));
    }

    com.javaclaw.sdk.model.BrowserSiteInfo site(com.javaclaw.protocol.WireBrowserSite value) {
        return new com.javaclaw.sdk.model.BrowserSiteInfo(
                value.id(),
                value.workspaceId(),
                value.name(),
                URI.create(value.origin()),
                value.allowedOrigins().stream().map(URI::create).collect(java.util.stream.Collectors.toSet()),
                value.enabled(),
                value.revision(),
                Instant.parse(value.updatedAt()));
    }

    com.javaclaw.sdk.model.NetworkGrantInfo grant(com.javaclaw.protocol.WireNetworkGrant value) {
        return new com.javaclaw.sdk.model.NetworkGrantInfo(
                value.id(),
                value.workspaceId(),
                value.purpose(),
                URI.create(value.origin()),
                java.util.Set.copyOf(value.addresses()),
                Instant.parse(value.expiresAt()),
                value.enabled(),
                value.revision(),
                Instant.parse(value.updatedAt()));
    }

    List<com.javaclaw.sdk.model.ModelCapabilityInfo> modelCapabilities(JsonNode values) {

        var result = new java.util.ArrayList<com.javaclaw.sdk.model.ModelCapabilityInfo>();
        values.forEach(value -> result.add(new com.javaclaw.sdk.model.ModelCapabilityInfo(
                value.path("provider").asText(), value.path("defaultModel").asText(),
                value.path("configured").asBoolean(),
                        value.path("configurationHint").asText())));
        return List.copyOf(result);
    }

    List<com.javaclaw.sdk.model.ToolCapabilityInfo> toolCapabilities(JsonNode values) {

        var result = new java.util.ArrayList<com.javaclaw.sdk.model.ToolCapabilityInfo>();
        values.forEach(value -> result.add(new com.javaclaw.sdk.model.ToolCapabilityInfo(
                value.path("name").asText(),
                value.path("description").asText(),
                new com.javaclaw.sdk.model.JsonDocument(
                        value.path("inputSchemaJson").asText()))));
        return List.copyOf(result);
    }
}
