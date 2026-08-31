package com.javaclaw.agent.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.prompt.ContextBlock;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.LearningResponses;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ThreadItem;

/** 同一 Agent Loop 的无工具知识维护步骤；不读取完整会话，不开放第二套运行时或自动执行模型建议。 */
public final class KnowledgeMaintenanceExecution {
    private final KnowledgeRepository knowledge;
    private final KnowledgeMaintenanceRepository tasks;

    /** 注入来源查询和知识仓库公开端口，权限与冲突判断仍在写入仓库时复核。 */
    public KnowledgeMaintenanceExecution(KnowledgeRepository knowledge, KnowledgeMaintenanceRepository tasks) {
        this.knowledge = java.util.Objects.requireNonNull(knowledge);
        this.tasks = java.util.Objects.requireNonNull(tasks);
    }

    /** 用固定用途和有限 TurnScope 提取候选；模式被关闭时不发出模型请求。 */
    public void execute(TurnExecutionContext context, ItemSink sink, ModelInvocationService models) throws Exception {
        String source = context.turn().config().attributes().get("maintenanceSourceTurn");
        String workspace = context.thread().workspaceId();
        var preferences = knowledge.learningSettings(workspace);
        var evidence = tasks.evidence(source, workspace);
        if (preferences.memoryAutomatic()) {
            extract(false, evidence, source, context, sink, models);
        }
        // 用户在两个模型调用间关闭学习应立即生效；旧快照不能使自动写入永久获得授权。
        preferences = knowledge.learningSettings(workspace);
        if (!"OFF".equals(preferences.skillMode())) {
            extract(
                    true,
                    evidence.stream()
                            .filter(KnowledgeMaintenanceRepository.Evidence::skillEvidence)
                            .toList(),
                    source,
                    context,
                    sink,
                    models);
        }
    }

    private void extract(
            boolean skill,
            List<KnowledgeMaintenanceRepository.Evidence> evidence,
            String source,
            TurnExecutionContext context,
            ItemSink sink,
            ModelInvocationService models)
            throws Exception {
        if (evidence.isEmpty()) {
            return;
        }
        context.throwIfInterrupted();
        var contract = skill ? LearningResponses.skill() : LearningResponses.memory();
        var references = evidence.stream()
                .map(item -> new ContextBlock(ContextBlock.Kind.REFERENCE, item.kind(), item.itemId(), 1, item.text()))
                .toList();
        var prepared = models.withContract(
                models.prepare(
                        skill ? PromptPurpose.SKILL_EXTRACTION : PromptPurpose.MEMORY_EXTRACTION,
                        context.turn().config(),
                        List.of(),
                        references,
                        com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(
                                context.thread().workingDirectory()),
                        List.of(new ModelMessage(
                                ModelMessage.Role.USER,
                                "仅从本次列出的来源提取可验证的低风险内容。允许 candidates 为空；不推断用户敏感属性，不把一次提问当长期偏好。"
                                        + "来源编号必须逐字引用，不能虚构已成功的操作。不需要新增时返回空列表。",
                                null))),
                contract);
        var response = models.complete(context, prepared, sink);
        var candidates = models.validateOrRepair(context, prepared, prepared.messages(), response, contract, sink);
        Set<String> allowed = evidence.stream()
                .map(KnowledgeMaintenanceRepository.Evidence::itemId)
                .collect(Collectors.toSet());
        int accepted = 0;
        for (var candidate : candidates) {
            context.throwIfInterrupted();
            if (!allowed.containsAll(candidate.sources())) {
                sink.append(new ThreadItem.ErrorItem("maintenance_source_invalid", "学习候选引用未提供的来源，已拒绝", false));
                continue;
            }
            try {
                MemorySafety.requireNoSecrets(candidate.content());
                MemorySafety.requireNoSecrets(candidate.reason());
                String key = "maintenance-" + source + "-" + PromptHashes.sha256(candidate.toString());
                if (skill) {
                    var proposal = knowledge.proposeSkill(
                            new LearningRepository.SkillDraft(
                                    context.thread().workspaceId(),
                                    null,
                                    candidate.subject(),
                                    "1.0",
                                    LearningResponses.skillManifest(candidate.content()),
                                    candidate.sources(),
                                    0),
                            true,
                            candidate.reason(),
                            key);
                    sink.append(new ThreadItem.DynamicToolCall(
                            "skill_learning",
                            Map.of("proposalId", proposal.id(), "state", proposal.state(), "sourceTurnId", source)));
                } else {
                    if (!knowledge
                            .learningSettings(context.thread().workspaceId())
                            .memoryAutomatic()) {
                        break;
                    }
                    var proposal = knowledge.proposeMemory(
                            new MemoryRepository.MemoryDraft(
                                    null,
                                    context.thread().workspaceId(),
                                    candidate.kind(),
                                    candidate.subject(),
                                    candidate.attribute(),
                                    candidate.content(),
                                    false,
                                    candidate.sources()),
                            0,
                            true,
                            candidate.reason(),
                            key);
                    sink.append(new ThreadItem.DynamicToolCall(
                            "memory_learning",
                            Map.of("proposalId", proposal.id(), "state", proposal.state(), "sourceTurnId", source)));
                }
                accepted++;
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // 候选可能包含私密内容；异常消息不进入持久事件或诊断。
                sink.append(
                        new ThreadItem.ErrorItem("maintenance_proposal_rejected", "学习候选未通过来源、内容或版本校验；没有覆盖用户内容", false));
            }
        }
        sink.append(new ThreadItem.DynamicToolCall(
                "knowledge_maintenance",
                Map.of(
                        "kind",
                        skill ? "skill" : "memory",
                        "proposals",
                        Integer.toString(accepted),
                        "sourceTurnId",
                        source)));
    }
}
