package com.javaclaw.server.transport;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.automation.AutomationRepository;
import com.javaclaw.agent.knowledge.KnowledgeRepository;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.AttachmentMetadata;
import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.core.api.ItemDelta;
import com.javaclaw.core.api.LiveItemEvent;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.protocol.WireAttachment;
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
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.configuration.ConfigurationState;
import com.javaclaw.server.diagnostics.DiagnosticsSnapshot;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.extension.McpServerState;
import com.javaclaw.server.extension.PluginStateView;
import com.javaclaw.server.extension.PluginTrustKeyView;
import com.javaclaw.server.extension.mcp.McpDiscovery;
import com.javaclaw.server.extension.mcp.McpDiscoveryStatus;
import com.javaclaw.server.model.CloudModelDescriptor;
import com.javaclaw.server.model.ProviderState;
import com.javaclaw.server.security.SecretStore;

/** Explicit and exhaustive domain-to-wire mapping boundary. */
public final class ProtocolMapper {
    com.javaclaw.protocol.WireWorktree worktree(
            com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.RecoveryInfo value) {
        return new com.javaclaw.protocol.WireWorktree(
                value.id(),
                value.workspaceId(),
                value.parentThreadId().value(),
                value.childThreadId().value(),
                value.state(),
                value.revision(),
                value.running(),
                value.backupSha256(),
                value.details(),
                value.updatedAt().toString());
    }

    com.javaclaw.protocol.WireWorktreePatch worktreePatch(
            com.javaclaw.agent.collaboration.CollaborationGateway.PatchResult value) {
        return new com.javaclaw.protocol.WireWorktreePatch(
                value.status(), value.patchAttachmentSha256(), value.conflicts(), value.message());
    }

    com.javaclaw.protocol.WirePromptPreview promptPreview(
            com.javaclaw.agent.conversation.ProfilePromptUseCases.Preview value) {
        return new com.javaclaw.protocol.WirePromptPreview(
                value.profileId(),
                value.revision(),
                value.editablePrompt(),
                value.purpose(),
                value.layers().stream()
                        .map(layer -> new com.javaclaw.protocol.WirePromptPreview.Layer(
                                layer.id(), layer.version(), layer.sha256()))
                        .toList(),
                value.tools(),
                value.warnings());
    }

    com.javaclaw.protocol.WireAgentsInstructionResolution instructions(
            com.javaclaw.agent.prompt.AgentsInstructionResolution value) {
        return new com.javaclaw.protocol.WireAgentsInstructionResolution(
                value.workingDirectory().toString(),
                value.sources().stream()
                        .map(source -> new com.javaclaw.protocol.WireAgentsInstructionSource(
                                source.scope(),
                                source.path().toString(),
                                source.bytes(),
                                source.sha256(),
                                source.truncated()))
                        .toList(),
                value.warnings(),
                value.totalProjectBytes());
    }

    private final ObjectMapper json;

    /** 创建显式领域到 Wire 映射器；json 非空，仅用于已声明 JSON 字段的解析，不反射序列化领域对象。 */
    public ProtocolMapper(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 将非空模型描述符列表映射为发现响应；保留配置状态，不输出模型凭据。 */
    public JsonNode models(java.util.List<CloudModelDescriptor> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> {
            ObjectNode model = result.addObject();
            model.put("provider", value.provider());
            model.put("defaultModel", value.defaultModel());
            model.put("configured", value.configured());
            model.put("configurationHint", value.configurationHint());
        });
        return result;
    }

    /** 将非空工具描述符列表映射为名称、说明及 Schema；保持调用方提供的目录顺序。 */
    public JsonNode tools(java.util.List<ToolDescriptor> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> {
            ObjectNode tool = result.addObject();
            tool.put("name", value.name());
            tool.put("description", value.description());
            tool.put("inputSchemaJson", value.inputSchemaJson());
        });
        return result;
    }

    /** 将插件进程发现列表映射为声明能力视图；这些元数据不构成运行时授权。 */
    public JsonNode discoveredProcesses(java.util.List<ServerDiscovery.ProcessView> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> result.add(discoveredProcess(value)));
        return result;
    }

    /** 将插件发现列表映射为插件、进程和 Skill 贡献视图；签名状态与权限声明分别保留。 */
    public JsonNode discoveredPlugins(java.util.List<ServerDiscovery.PluginView> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> {
            ObjectNode plugin = result.addObject();
            plugin.put("id", value.id());
            plugin.put("name", value.name());
            plugin.put("version", value.version());
            plugin.put("apiVersion", value.apiVersion());
            plugin.put("signatureVerified", value.signatureVerified());
            ArrayNode processes = plugin.putArray("processes");
            value.processes().forEach(process -> processes.add(discoveredProcess(process)));
            ArrayNode skills = plugin.putArray("skills");
            value.skills().forEach(valueSkill -> {
                ObjectNode skill = skills.addObject();
                skill.put("id", valueSkill.id());
                skill.put("path", valueSkill.path());
            });
        });
        return result;
    }

    private static ObjectNode discoveredProcess(ServerDiscovery.ProcessView value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("pluginId", value.pluginId());
        result.put("id", value.id());
        result.put("kind", value.kind());
        result.put("workspaceRead", value.workspaceRead());
        result.put("workspaceWrite", value.workspaceWrite());
        ArrayNode hosts = result.putArray("networkAllowlist");
        value.networkAllowlist().forEach(hosts::add);
        return result;
    }

    /** 映射非空 Thread，保留可空父分支标识、持久 sequence 和 revision，不重新推断分支关系。 */
    public WireThread thread(AgentThread value) {
        return new WireThread(
                value.id().value(),
                value.workspaceId(),
                value.parentThreadId() == null ? null : value.parentThreadId().value(),
                value.forkedFromTurnId() == null
                        ? null
                        : value.forkedFromTurnId().value(),
                value.title(),
                value.workingDirectory().toString(),
                value.status().name(),
                value.baseSequence(),
                value.lastSequence(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 映射非空 Workspace 的规范根路径和锁定状态；不授予客户端任意路径访问权。 */
    public WireWorkspace workspace(Workspace value) {
        return new WireWorkspace(
                value.id().value(),
                value.name(),
                value.root().toString(),
                value.revision(),
                value.locked(),
                value.lockReason(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 映射非空版本化 Profile，保留预算、工具集合和请求策略；最终 Turn 权限仍由服务端解析。 */
    public WireProfile profile(ExecutionProfile value) {
        return new WireProfile(
                value.id(),
                value.name(),
                value.kind().name(),
                value.provider(),
                value.model(),
                value.systemPrompt(),
                value.enabledTools(),
                value.requestedSandboxMode().name(),
                value.maxIterations(),
                value.maxModelCalls(),
                value.attributes(),
                value.revision(),
                value.updatedAt());
    }

    /** 映射非空附件元数据，仅暴露内容摘要、媒体类型、字节大小及引用数，不暴露 blob 本地路径。 */
    public WireAttachment attachment(AttachmentMetadata value) {
        return new WireAttachment(value.sha256(), value.mediaType(), value.sizeBytes(), value.referenceCount());
    }

    /** 映射非空自动化定义并解析 definitionJson；持久定义不是有效 JSON 时抛出 IllegalStateException。 */
    public WireAutomation automation(AutomationRepository.AutomationDefinition value) {
        try {
            return new WireAutomation(
                    value.id(),
                    value.kind().name(),
                    value.name(),
                    value.workspaceId(),
                    value.profileId(),
                    value.prompt(),
                    json.readTree(value.definitionJson()),
                    value.status(),
                    value.threadId(),
                    value.activeTurnId(),
                    value.revision(),
                    value.createdAt(),
                    value.updatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("persisted automation definition is invalid", failure);
        }
    }

    /** 映射非空调度定义及上次、下次触发时间；保留尚未触发时的可空状态。 */
    public WireSchedule schedule(AutomationRepository.ScheduleDefinition value) {
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

    /** 映射当前知识 generation 的安全统计；失败详情已在领域边界脱敏。 */
    public com.javaclaw.protocol.WireKnowledgeSourceStats knowledgeSourceStats(
            KnowledgeRepository.KnowledgeSourceStats value) {
        return new com.javaclaw.protocol.WireKnowledgeSourceStats(
                value.sourceId(),
                value.generation(),
                value.chunkCount(),
                value.retrievalMode(),
                value.indexedAt(),
                value.failureSummary());
    }

    /** 映射非空 Memory 内容及其 Workspace、revision 和时间，不在映射时检索或更新记忆。 */
    public WireMemory memory(KnowledgeRepository.MemoryEntry value) {
        return new WireMemory(
                value.id(),
                value.workspaceId(),
                value.kind(),
                value.content(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 将记忆内容与版本显式投影为协议 DTO，不直接序列化 Repository 对象。 */
    public com.javaclaw.protocol.WireMemoryDetail memoryDetail(
            com.javaclaw.agent.knowledge.MemoryRepository.MemoryDocument value) {
        var draft = value.draft();
        return new com.javaclaw.protocol.WireMemoryDetail(
                draft.id(),
                draft.workspaceId(),
                draft.kind(),
                draft.subject(),
                draft.attribute(),
                draft.content(),
                draft.pinned(),
                draft.sourceItemIds(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 显式映射提案的建议内容、目标修订与审批状态，不将提案当作已采纳事实。 */
    public com.javaclaw.protocol.WireMemoryProposal memoryProposal(
            com.javaclaw.agent.knowledge.MemoryRepository.MemoryProposal value) {
        var draft = value.draft();
        var detail = new com.javaclaw.protocol.WireMemoryDetail(
                draft.id(),
                draft.workspaceId(),
                draft.kind(),
                draft.subject(),
                draft.attribute(),
                draft.content(),
                draft.pinned(),
                draft.sourceItemIds(),
                value.expectedTargetRevision(),
                null,
                null);
        return new com.javaclaw.protocol.WireMemoryProposal(
                value.id(),
                detail,
                value.expectedTargetRevision(),
                value.state(),
                value.reason(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 映射非空知识源及附件摘要、索引状态和 revision；不加载原始文档。 */
    public WireKnowledgeSource knowledgeSource(KnowledgeRepository.KnowledgeSource value) {
        return new WireKnowledgeSource(
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

    /** 映射非空检索命中，保留原始评分和来源 revision；不重新排序或归一化评分。 */
    public WireKnowledgeHit knowledgeHit(KnowledgeRepository.SearchHit value) {
        return new WireKnowledgeHit(
                value.sourceId(),
                value.chunkId(),
                value.displayName(),
                value.content(),
                value.score(),
                value.sourceRevision());
    }

    /** 工作区学习偏好；AUTO 不是权限授权。 */
    public com.javaclaw.protocol.WireLearningSettings learningSettings(
            com.javaclaw.agent.knowledge.LearningRepository.LearningSettings value) {
        return new com.javaclaw.protocol.WireLearningSettings(
                value.workspaceId(), value.skillMode(), value.memoryAutomatic(), value.revision(), value.updatedAt());
    }

    /** 不可变 Skill 资源；路径仅为 Bundle 内标识，不能用作客户端或宿主绝对路径。 */
    public com.javaclaw.protocol.WireSkillResource skillResource(com.javaclaw.agent.knowledge.SkillResource value) {
        return new com.javaclaw.protocol.WireSkillResource(
                value.path(), value.mediaType(), value.content(), value.executable());
    }

    /** 已完成的知识索引 generation；未完成重建不会成为当前投影。 */
    public com.javaclaw.protocol.WireKnowledgeGeneration knowledgeGeneration(
            com.javaclaw.agent.knowledge.KnowledgeRepository.KnowledgeGeneration value) {
        return new com.javaclaw.protocol.WireKnowledgeGeneration(
                value.sourceId(),
                value.revision(),
                value.contentSha256(),
                value.extractorFingerprint(),
                value.status(),
                value.createdAt());
    }

    /** 带来源、目标版本和审阅状态的 Skill 学习提案。 */
    public com.javaclaw.protocol.WireSkillProposal skillProposal(
            com.javaclaw.agent.knowledge.LearningRepository.SkillProposal value) {
        return new com.javaclaw.protocol.WireSkillProposal(
                value.id(),
                value.draft().workspaceId(),
                value.draft().targetId(),
                value.draft().name(),
                value.draft().version(),
                value.draft().manifest(),
                value.draft().sourceItemIds(),
                value.draft().expectedTargetRevision(),
                value.state(),
                value.reason(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    /** 映射非空 Skill 元数据并解析 manifest；持久 manifest 不是有效 JSON 时抛出 IllegalStateException。 */
    public WireSkill skill(KnowledgeRepository.SkillEntry value) {
        try {
            return new WireSkill(
                    value.id(),
                    value.name(),
                    value.version(),
                    json.readTree(value.manifest()),
                    value.enabled(),
                    value.revision(),
                    value.createdAt(),
                    value.updatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("persisted skill manifest is invalid", failure);
        }
    }

    /** 映射非空 Provider 配置状态，仅返回配置和凭据版本等元数据，不返回密钥。 */
    public WireProvider provider(ProviderState value) {
        return new WireProvider(
                value.id(),
                value.configured(),
                value.credentialRevision(),
                value.configRevision(),
                value.model(),
                value.embeddingModel(),
                value.baseUrl(),
                value.updatedAt());
    }

    /** 映射非空插件运维视图，分别保留签名、来源确认、权限审批与运行状态。 */
    public WirePlugin plugin(PluginStateView value) {
        return new WirePlugin(
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

    /** 映射非空信任公钥的标识、指纹与版本；信任来源不等于授权插件权限。 */
    public WirePluginTrustKey pluginTrustKey(PluginTrustKeyView value) {
        return new WirePluginTrustKey(
                value.keyId(), value.label(), value.fingerprintSha256(), value.revision(), value.createdAt());
    }

    /** 映射非空 MCP 状态并解析配置元数据；无效配置 JSON 抛出 IllegalStateException，输入配置不得包含明文凭据。 */
    public WireMcpServer mcpServer(McpServerState value) {
        try {
            return new WireMcpServer(
                    value.id(),
                    value.pluginId(),
                    value.name(),
                    json.readTree(value.configurationJson()),
                    value.enabled(),
                    value.state(),
                    value.revision(),
                    value.updatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("persisted MCP config is invalid", failure);
        }
    }

    /** 映射非空 MCP 发现状态；尚未发现时保留 null，已发现的扩展 JSON 使用深拷贝避免共享可变节点。 */
    public JsonNode mcpDiscovery(McpDiscoveryStatus value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", value.id());
        result.put("revision", value.revision());
        result.put("enabled", value.enabled());
        result.put("state", value.state());
        McpDiscovery discovery = value.discovery();
        if (discovery == null) {
            result.putNull("discovery");
            return result;
        }
        ObjectNode payload = result.putObject("discovery");
        ArrayNode versions = payload.putArray("supportedVersions");
        discovery.supportedVersions().forEach(versions::add);
        payload.set("capabilities", discovery.capabilities().deepCopy());
        payload.put("instructions", discovery.instructions());
        payload.put("serverName", discovery.serverName());
        payload.put("serverVersion", discovery.serverVersion());
        payload.set("raw", discovery.raw().deepCopy());
        return result;
    }

    /** 映射非空凭据元数据；只返回命名空间、名称、configured、revision 和更新时间。 */
    public WireSecretMetadata secretMetadata(SecretStore.SecretMetadata value) {
        return new WireSecretMetadata(
                value.namespace(), value.name(), value.configured(), value.revision(), value.updatedAt());
    }

    /** 将非空配置状态中的显式 JSON 值还原为对象；无效持久 JSON 抛出 IllegalStateException。调用方须提供已过滤敏感值的配置。 */
    public JsonNode configuration(ConfigurationState value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        value.values().forEach((key, encoded) -> {
            try {
                result.set(key, json.readTree(encoded));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("configuration JSON is invalid: " + key, failure);
            }
        });
        return result;
    }

    /** 映射非空诊断快照并解析各记录详情；不读取环境或 SecretStore，无效详情 JSON 抛出 IllegalStateException。 */
    public JsonNode diagnostics(DiagnosticsSnapshot value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("format", value.format());
        result.put("generatedAt", value.generatedAt().toString());
        result.put("javaVersion", value.javaVersion());
        result.put("osName", value.osName());
        result.put("osArch", value.osArch());
        result.put("availableProcessors", value.availableProcessors());
        result.put("credentialsIncluded", value.credentialsIncluded());
        result.put("environmentIncluded", value.environmentIncluded());
        ArrayNode records = result.putArray("records");
        value.records().forEach(valueRecord -> {
            ObjectNode record = records.addObject();
            record.put("id", valueRecord.id());
            record.put("severity", valueRecord.severity());
            record.put("component", valueRecord.component());
            record.put("code", valueRecord.code());
            record.put("message", valueRecord.message());
            try {
                record.set("details", json.readTree(valueRecord.detailsJson()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("diagnostic details JSON is invalid", failure);
            }
            record.put("createdAt", valueRecord.createdAt().toString());
        });
        return result;
    }

    /** 映射非空 Turn，显式转换输入和已固化配置，保留执行 attempt 与终态错误信息。 */
    public WireTurn turn(AgentTurn value) {
        return new WireTurn(
                value.id().value(),
                value.threadId().value(),
                value.status().name(),
                value.attemptId().value(),
                value.input().stream().map(this::turnInput).toList(),
                turnConfig(value.config()),
                value.error(),
                value.startedAt(),
                value.completedAt());
    }

    /** 映射非空持久 Item；尚无最终内容时输出 JSON null，不为 STARTED Item 伪造最终结果。 */
    public WireItem item(StoredItem value) {
        return new WireItem(
                value.id().value(),
                value.threadId().value(),
                value.turnId().value(),
                value.ordinal(),
                value.state().name(),
                value.kind(),
                value.item() == null ? JsonNodeFactory.instance.nullNode() : itemContent(value.item()),
                value.createdAt(),
                value.updatedAt());
    }

    /** 映射非空持久事件，保持 schemaVersion、sequence、关联标识和原字符串 payload，不消耗新 sequence。 */
    public WireEvent event(ThreadEvent value) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        value.payload().forEach(payload::put);
        return new WireEvent(
                value.eventId(),
                value.threadId().value(),
                value.turnId() == null ? null : value.turnId().value(),
                value.sequence(),
                value.type(),
                value.schemaVersion(),
                value.correlationId(),
                value.causationId(),
                payload,
                value.timestamp());
    }

    /** 映射非空 Thread 快照，保持其中 Turn 与 Item 的既定顺序；不访问数据库补充状态。 */
    public WireThreadSnapshot snapshot(ThreadSnapshot value) {
        return new WireThreadSnapshot(
                thread(value.thread()),
                value.turns().stream().map(this::turn).toList(),
                value.items().stream().map(this::item).toList());
    }

    /** 穷举映射非空文本或附件引用；附件仅携带 SHA-256、媒体类型和展示名，不发送本地路径。 */
    public JsonNode turnInput(TurnInput input) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("type", input.type());
        switch (input) {
            case TurnInput.Text text -> result.put("text", text.text());
            case TurnInput.AttachmentRef attachment -> {
                result.put("sha256", attachment.sha256());
                result.put("mediaType", attachment.mediaType());
                result.put("displayName", attachment.displayName());
            }
        }
        return result;
    }

    /** 显式映射非空已解析 Turn 配置；对集合和属性排序以稳定 Wire 输出，不改变服务端已确定的权限。 */
    public JsonNode turnConfig(TurnConfig config) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("model", config.model());
        result.put("provider", config.provider());
        result.put("reasoningEffort", config.reasoningEffort());
        result.put("workingDirectory", config.workingDirectory().toString());
        result.set("sandboxPolicy", sandboxPolicy(config.sandboxPolicy()));
        result.put("approvalPolicy", config.approvalPolicy().name());
        ArrayNode tools = result.putArray("enabledTools");
        config.enabledTools().stream().sorted().forEach(tools::add);
        ObjectNode attributes = result.putObject("attributes");
        config.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> attributes.put(entry.getKey(), entry.getValue()));
        return result;
    }

    /** 穷举映射非空已知 Item 的最终内容；reasoning 仅包含可展示摘要，不序列化原始思维链。 */
    public JsonNode itemContent(ThreadItem item) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        switch (item) {
            case ThreadItem.Checkpoint value -> {
                result.put("executionId", value.executionId())
                        .put("definitionHash", value.definitionHash())
                        .put("stepId", value.stepId())
                        .put("status", value.status())
                        .put("iteration", value.iteration())
                        .put("usedModelCalls", value.usedModelCalls())
                        .put("usedTokens", value.usedTokens())
                        .put("elapsedMillis", value.elapsedMillis())
                        .put("summary", value.summary());
                putStringMap(result.putObject("outputs"), value.outputs());
                value.completedSteps().forEach(result.putArray("completedSteps")::add);
            }
            case ThreadItem.Evaluation value -> {
                result.put("scope", value.scope()).put("passed", value.passed()).put("summary", value.summary());
                value.evidenceItemIds().forEach(result.putArray("evidenceItemIds")::add);
                value.remaining().forEach(result.putArray("remaining")::add);
            }
            case ThreadItem.Artifact value -> {
                result.put("artifactId", value.artifactId())
                        .put("category", value.category())
                        .put("name", value.name())
                        .put("revision", value.revision())
                        .put("content", value.content());
                value.sources().forEach(result.putArray("sources")::add);
            }
            case ThreadItem.EffectReceipt value -> {
                result.put("key", value.key())
                        .put("tool", value.tool())
                        .put("state", value.state())
                        .put("summary", value.summary());
                // 凭据只公开已脱敏的原工具结果；不增加可重放的原始参数或秘密。
                if (value.result() != null) {
                    result.putObject("result")
                            .put("kind", value.result().item().kind())
                            .put("modelContent", value.result().modelContent())
                            .set("item", itemContent(value.result().item()));
                }
            }
            case ThreadItem.UserMessage value -> {
                result.put("text", value.text());
                if (!value.attachments().isEmpty()) {
                    ArrayNode attachments = result.putArray("attachments");
                    value.attachments()
                            .forEach(reference -> attachments
                                    .addObject()
                                    .put("sha256", reference.sha256())
                                    .put("mediaType", reference.mediaType())
                                    .put("displayName", reference.displayName()));
                }
            }
            case ThreadItem.AgentMessage value -> result.put("text", value.text());
            case ThreadItem.ReasoningSummary value -> result.put("text", value.text());
            case ThreadItem.Plan value -> {
                ArrayNode steps = result.putArray("steps");
                value.steps().forEach(step -> {
                    ObjectNode node = steps.addObject();
                    node.put("step", step.step());
                    node.put("status", step.status().name());
                });
                if (!value.details().equals(ThreadItem.Plan.PlanDetails.EMPTY)) {
                    var details = value.details();
                    ObjectNode node = result.putObject("details");
                    node.put("goal", details.goal()).put("scope", details.scope());
                    details.dependencies().forEach(node.putArray("dependencies")::add);
                    details.acceptanceCriteria().forEach(node.putArray("acceptanceCriteria")::add);
                    details.risks().forEach(node.putArray("risks")::add);
                    details.openQuestions().forEach(node.putArray("openQuestions")::add);
                }
            }
            case ThreadItem.PromptDraft value -> {
                result.put("profileId", value.profileId())
                        .put("expectedRevision", value.expectedRevision())
                        .put("draft", value.draft());
                value.changes().forEach(result.putArray("changes")::add);
                value.warnings().forEach(result.putArray("warnings")::add);
            }
            case ThreadItem.CommandExecution value -> {
                ArrayNode argv = result.putArray("argv");
                value.argv().forEach(argv::add);
                result.put("exitCode", value.exitCode());
                result.put("stdout", value.stdout());
                result.put("stderr", value.stderr());
                result.put("timedOut", value.timedOut());
                result.put("truncated", value.truncated());
            }
            case ThreadItem.FileChange value -> {
                result.put("path", value.path());
                result.put("change", value.change().name());
                result.put("diff", value.diff());
            }
            case ThreadItem.McpToolCall value -> {
                result.put("server", value.server());
                result.put("tool", value.tool());
                putStringMap(result.putObject("result"), value.result());
            }
            case ThreadItem.DynamicToolCall value -> {
                result.put("tool", value.tool());
                putStringMap(result.putObject("result"), value.result());
            }
            case ThreadItem.ApprovalRequest value -> {
                result.put("approvalId", value.approvalId());
                result.put("reason", value.reason());
                result.put("risk", value.risk());
            }
            case ThreadItem.UserInputRequest value -> {
                result.put("requestId", value.requestId());
                result.put("prompt", value.prompt());
                ArrayNode choices = result.putArray("choices");
                value.choices().forEach(choices::add);
            }
            case ThreadItem.UserInputResponse value -> {
                result.put("requestId", value.requestId());
                result.put("value", value.value());
                result.put("cancelled", value.cancelled());
            }
            case ThreadItem.SubagentCall value -> {
                result.put("childThreadId", value.childThreadId().value());
                result.put("task", value.task());
                result.put("summary", value.summary());
            }
            case ThreadItem.WebSearch value -> {
                result.put("query", value.query());
                ArrayNode sources = result.putArray("sources");
                value.sources().forEach(sources::add);
            }
            case ThreadItem.ImageView value -> {
                result.put("uri", value.uri());
                result.put("description", value.description());
            }
            case ThreadItem.ContextUsage value -> {
                result.put("source", value.source());
                result.put("sourceId", value.sourceId());
                result.put("revision", value.revision());
                result.put("summary", value.summary());
            }
            case ThreadItem.ContextCompaction ignored -> {}
            case ThreadItem.ErrorItem value -> {
                result.put("code", value.code());
                result.put("message", value.message());
                result.put("retryable", value.retryable());
            }
        }
        return result;
    }

    /** 映射非空内存 delta 的内容类型、文本和元数据；不写持久事件，也不分配 Thread sequence。 */
    public JsonNode itemDelta(ItemDelta value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("contentType", value.contentType());
        result.put("text", value.text());
        putStringMap(result.putObject("metadata"), value.metadata());
        return result;
    }

    /** 映射非空活动 Item 生命周期或 delta；保留逐 Item 的 deltaSequence，生命周期通知的 delta 可为 null。 */
    public JsonNode liveItem(LiveItemEvent value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("threadId", value.threadId().value());
        result.put("turnId", value.turnId().value());
        result.put("itemId", value.itemId().value());
        result.put("kind", value.kind());
        result.put("deltaSequence", value.deltaSequence());
        if (value.delta() == null) {
            result.putNull("delta");
        } else {
            result.set("delta", itemDelta(value.delta()));
        }
        result.put("phase", value.phase().name());
        result.put("timestamp", value.timestamp().toString());
        return result;
    }

    private static JsonNode sandboxPolicy(SandboxPolicy policy) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("mode", policy.mode().name());
        putPaths(result.putArray("readableRoots"), policy.readableRoots());
        putPaths(result.putArray("writableRoots"), policy.writableRoots());
        putPaths(result.putArray("protectedRoots"), policy.protectedRoots());
        NetworkPolicy network = policy.network();
        ObjectNode networkNode = result.putObject("network");
        networkNode.put("mode", network.mode().name());
        ArrayNode hosts = networkNode.putArray("allowedHosts");
        network.allowedHosts().stream().sorted().forEach(hosts::add);
        ArrayNode environment = result.putArray("inheritedEnvironment");
        policy.inheritedEnvironment().stream().sorted().forEach(environment::add);
        result.put("timeoutMillis", policy.timeout().toMillis());
        result.put("outputLimitBytes", policy.outputLimitBytes());
        return result;
    }

    private static void putPaths(ArrayNode target, Iterable<Path> paths) {
        java.util.stream.StreamSupport.stream(paths.spliterator(), false)
                .map(Path::toString)
                .sorted()
                .forEach(target::add);
    }

    private static void putStringMap(ObjectNode target, Map<String, String> values) {
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> target.put(entry.getKey(), entry.getValue()));
    }

    com.javaclaw.protocol.WireBrowserSite site(com.javaclaw.server.browser.BrowserSite value) {
        return new com.javaclaw.protocol.WireBrowserSite(
                value.id(),
                value.workspaceId(),
                value.name(),
                value.origin().toString(),
                value.allowedOrigins().stream()
                        .map(java.net.URI::toString)
                        .sorted()
                        .toList(),
                value.enabled(),
                value.revision(),
                value.updatedAt().toString());
    }

    com.javaclaw.protocol.WireNetworkGrant networkGrant(com.javaclaw.server.network.NetworkGrant value) {
        return new com.javaclaw.protocol.WireNetworkGrant(
                value.id(),
                value.workspaceId(),
                value.purpose(),
                value.origin().toString(),
                value.addresses().stream().sorted().toList(),
                value.expiresAt().toString(),
                value.enabled(),
                value.revision(),
                value.updatedAt().toString());
    }

    com.javaclaw.protocol.WireToolAuthorization toolAuthorization(
            com.javaclaw.server.extension.ToolAuthorization value) {
        return new com.javaclaw.protocol.WireToolAuthorization(
                value.id(),
                value.workspaceId(),
                value.sourceId(),
                value.toolName(),
                value.sourceRevision(),
                value.schemaSha256(),
                value.argumentTemplate(),
                value.recipientField(),
                value.variableFields().stream().sorted().toList(),
                value.maximumUses(),
                value.consumedUses(),
                value.expiresAt().toString(),
                value.enabled(),
                value.revision(),
                value.updatedAt().toString());
    }

    com.javaclaw.protocol.WireToolAuthorityOption toolAuthorityOption(
            com.javaclaw.server.extension.ToolAuthorityOption value) {
        return new com.javaclaw.protocol.WireToolAuthorityOption(
                value.sourceId(),
                value.toolName(),
                value.description(),
                value.sourceRevision(),
                value.schemaSha256(),
                value.inputSchemaJson());
    }
}
