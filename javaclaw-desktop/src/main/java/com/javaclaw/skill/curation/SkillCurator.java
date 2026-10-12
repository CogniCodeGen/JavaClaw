package com.javaclaw.skill.curation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.execution.ExecutionTrace;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.prompt.SkillPrompts;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillChangeRequest;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillUsageTracker;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.runtime.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 技能蒸馏器 —— 自学习闭环的被动兜底路径（借鉴 hermes-agent「复杂任务后自主创建技能」）。
 *
 * <p>与 {@code MemoryCurator}（陈述性记忆：记住"是什么"）互补，本类沉淀<b>程序性记忆</b>
 * （记住"怎么做"）：在一轮对话 / 一个 SDD 托管任务结束后，用轻量模型从执行轨迹中蒸馏
 * 「是否有值得沉淀的工作流经验」，产出 create 新技能或 patch 既有技能的结构化提案。</p>
 *
 * <p>触发门槛（对齐 Hermes）：
 * <ul>
 *   <li>工具调用 ≥ {@code skill.evolution.min.tools}（默认 5）且成功收尾</li>
 *   <li>或存在「踩坑恢复」信号（失败后转成功的轨迹），不足 min.tools 也触发</li>
 * </ul>
 *
 * <p>三态分流：off 不蒸馏；suggest 提案入 {@link SkillProposalQueue} 待审；
 * auto 直接落盘 + Toast 通知（user-modified 技能强制降级为提案）。
 * 与智能体主动 skill_manage 路径在队列层按指纹统一去重。</p>
 *
 * <p>关键设计：工作区托管任务异步执行、失败不影响主流程、CAS 防重、
 * 轻量模型控制成本。工作区关闭会取消在途蒸馏，迟到结果不会写入新工作区。</p>
 *
 * @author JavaClaw
 */
public class SkillCurator {

    private static final Logger log = LoggerFactory.getLogger(SkillCurator.class);
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();
    private static final JsonNode DRAFT_SCHEMA = draftSchema();

    /** 结构化蒸馏调用超时（秒） */
    private static final long STRUCTURED_TIMEOUT_SEC = 120;

    /** 参与蒸馏的对话/任务描述最大字符数（超过截尾） */
    private static final int MAX_CONTEXT_CHARS = 6000;

    private final AgentClient agents;
    private final ModelTaskGateway modelTasks;
    private final ObjectMapper json;
    private final WorkspaceContext workspace;
    private final SkillManager skills;
    private final SkillUsageTracker usage;
    private final SkillProposalQueue proposalQueue;
    private final AgentConfig settings;
    private final TaskSubmitter tasks;

    /** 获取交互端口（auto 模式 Toast 通知用；为 null 时静默跳过通知） */
    private final Supplier<UserInteractionPort> portSupplier;

    /** 蒸馏互斥：同时最多一个蒸馏在跑，避免连续轮次并发烧 token */
    private final AtomicBoolean distilling = new AtomicBoolean(false);

    public SkillCurator(AgentClient agents,
                        ModelTaskGateway modelTasks,
                        ObjectMapper json,
                        WorkspaceContext workspace,
                        SkillManager skills,
                        SkillUsageTracker usage,
                        SkillProposalQueue proposalQueue,
                        AgentConfig settings,
                        TaskSubmitter tasks,
                        Supplier<UserInteractionPort> portSupplier) {
        this.agents = java.util.Objects.requireNonNull(agents, "agents");
        this.modelTasks = java.util.Objects.requireNonNull(modelTasks, "modelTasks");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.usage = java.util.Objects.requireNonNull(usage, "usage");
        this.proposalQueue = java.util.Objects.requireNonNull(proposalQueue, "proposalQueue");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.portSupplier = portSupplier != null ? portSupplier : () -> null;
    }

    // ==================== 入口 1：聊天轮蒸馏 ====================

    /**
     * 异步从一轮完整对话蒸馏技能。失败静默，不影响主流程。
     *
     * @param userInput   用户输入
     * @param replyText   助手回复
     * @param traces      本轮执行轨迹
     * @param successRate 滑窗成功率
     */
    public Mono<Void> distillFromChatTurn(String userInput, String replyText,
                                          List<ExecutionTrace> traces, double successRate) {
        if (!shouldDistill(traces, successRate)) {
            return Mono.empty();
        }
        String context = "[用户请求]\n" + truncate(nz(userInput))
                + "\n\n[最终回复摘要]\n" + truncate(nz(replyText));
        return distillAsync(context, traces);
    }

    // ==================== 入口 2：SDD 任务蒸馏 ====================

    /**
     * 异步从一个完成的 SDD 托管任务蒸馏技能。SDD 任务天然是「复杂任务成功」的最强信号，
     * 不再按 min.tools 设门槛（轨迹可为空，凭任务描述与产出蒸馏）。
     *
     * @param title       任务标题
     * @param description 任务描述
     * @param outcomeSummary 任务产出摘要（如 proposal 的 why/whatChanges + 验收结论）
     */
    public Mono<Void> distillFromSddTask(String title, String description, String outcomeSummary) {
        String context = "[已完成的托管任务]\n标题：" + nz(title)
                + "\n描述：" + truncate(nz(description))
                + "\n\n[任务产出摘要]\n" + truncate(nz(outcomeSummary));
        return distillAsync(context, List.of());
    }

    // ==================== 触发门槛 ====================

    /**
     * 聊天轮蒸馏门槛：off 不蒸馏；工具调用达阈值且成功收尾；
     * 或存在「踩坑恢复」信号（有失败轨迹但最终成功率达标）——这正是 Hermes
     * 「踩坑后找到可行路径」最值得沉淀的时机。
     */
    private boolean shouldDistill(List<ExecutionTrace> traces, double successRate) {
        if ("off".equals(settings.getSkillEvolutionMode())) {
            return false;
        }
        if (traces == null || traces.isEmpty()) {
            return false;
        }
        boolean succeeded = successRate >= settings.getSkillEvolutionSuccessThreshold();
        if (!succeeded) {
            return false;
        }
        if (traces.size() >= settings.getSkillEvolutionMinTools()) {
            return true;
        }
        // 踩坑恢复：存在失败轨迹但整体成功收尾，即使不足 min.tools 也值得蒸馏
        return traces.stream().anyMatch(t -> !t.isSuccess());
    }

    // ==================== 蒸馏执行 ====================

    private Mono<Void> distillAsync(String context, List<ExecutionTrace> traces) {
        if ("off".equals(settings.getSkillEvolutionMode())) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
                    var handle = tasks.submit(
                            TaskSpec.io("skill-distill")
                                    .withTimeout(java.time.Duration.ofSeconds(
                                            STRUCTURED_TIMEOUT_SEC + 5)),
                            taskContext -> {
                                taskContext.cancellation().throwIfCancellationRequested();
                                distillSync(context, traces);
                                return null;
                            });
                    return Mono.fromFuture(handle.completion())
                            .doOnCancel(handle::cancel);
                })
                .onErrorResume(e -> {
                    log.warn("技能蒸馏失败（已静默忽略）: {}", e.getMessage());
                    distilling.set(false);
                    return Mono.empty();
                })
                .then();
    }

    private void distillSync(String context, List<ExecutionTrace> traces) {
        if (!distilling.compareAndSet(false, true)) {
            log.debug("已有技能蒸馏在执行，本次跳过");
            return;
        }
        try {
            SkillCurationDraft draft = callStructured(buildUserPrompt(context, traces));
            if (draft == null || !draft.worthLearning
                    || draft.action == null || "none".equalsIgnoreCase(draft.action)) {
                log.debug("本次经历无值得沉淀的技能经验");
                return;
            }
            dispatch(draft);
        } finally {
            distilling.set(false);
        }
    }

    /** 把蒸馏结果按三态分流：suggest 入队；auto 直接落盘 + Toast（user-modified 降级入队） */
    private void dispatch(SkillCurationDraft draft) {
        SkillChangeRequest request = toRequest(draft);
        if (request == null) {
            return;
        }

        String mode = settings.getSkillEvolutionMode();
        boolean targetUserModified = false;
        if ("patch".equals(request.action)) {
            Skill target = skills.getSkillByName(request.skillName);
            if (target == null) {
                log.info("蒸馏产出 patch 但目标技能「{}」不存在，丢弃", request.skillName);
                return;
            }
            targetUserModified = target.isUserModified();
            request.userModifiedWarning = targetUserModified;
        }

        if ("auto".equals(mode) && !targetUserModified) {
            String error = request.apply(skills);
            if (error != null) {
                log.info("自动沉淀技能失败（转提案待审）: {}", error);
                proposalQueue.submit(request);
                return;
            }
            log.info("已自动沉淀技能: [{}] {}", request.action, request.skillName);
            toast("技能自学习",
                    ("create".equals(request.action) ? "已沉淀新技能「" : "已更新技能「")
                            + request.skillName + "」：" + nz(request.reason));
        } else {
            // suggest 模式，或 auto 模式下 user-modified 保护降级
            String proposalId = proposalQueue.submit(request);
            if (proposalId != null) {
                log.info("蒸馏提案已入队待审: [{}] {} ({})", request.action, request.skillName, proposalId);
            }
        }
    }

    private SkillChangeRequest toRequest(SkillCurationDraft draft) {
        SkillChangeRequest request = new SkillChangeRequest();
        request.skillName = nz(draft.skillName).strip();
        request.reason = nz(draft.reason);
        if (request.skillName.isEmpty()) {
            return null;
        }
        if ("create".equalsIgnoreCase(draft.action)) {
            if (nz(draft.content).isBlank()) {
                return null;
            }
            // 蒸馏可能对既有技能产出 create：转为入队 patch 不可行（无 old/new），直接丢弃避免覆盖
            if (skills.getSkillByName(request.skillName) != null) {
                log.info("蒸馏产出 create 但技能「{}」已存在，丢弃", request.skillName);
                return null;
            }
            request.action = "create";
            request.description = nz(draft.description);
            request.category = nz(draft.category);
            request.tags = draft.tags != null ? draft.tags : new ArrayList<>();
            request.content = draft.content;
            return request;
        }
        if ("patch".equalsIgnoreCase(draft.action)) {
            if (nz(draft.oldString).isEmpty()) {
                return null;
            }
            request.action = "patch";
            request.oldString = draft.oldString;
            request.newString = nz(draft.newString);
            return request;
        }
        return null;
    }

    // ==================== 结构化模型任务（托管 Run 提供持久化归属） ====================

    private SkillCurationDraft callStructured(String userPrompt) {
        ManagedTurn owner = null;
        try {
            RunRequest request = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("system.default"))
                    .profile(RunProfileRef.latest("subagent"))
                    .source(InvocationSource.workflow("skill-distillation"))
                    .scope(new RunScope(workspace.workspaceId(), "local-user", "skill-distillation"))
                    .input(InputBlock.text(userPrompt))
                    .linkage(RunLinkage.root("skill-distillation"))
                    .permissionCeiling(PermissionSet.NONE)
                    .budget(new RunBudget(java.time.Duration.ofSeconds(STRUCTURED_TIMEOUT_SEC),
                            100_000, 20_000, 0, new java.math.BigDecimal("20")))
                    .idempotencyKey("skill-distillation:" + UUID.randomUUID())
                    .attributes(Map.of("framework.maintenance", JsonNodeFactory.instance.booleanNode(true)))
                    .build();
            owner = agents.beginTurn(request);
            owner.ready().toCompletableFuture().get(
                    STRUCTURED_TIMEOUT_SEC + 5, java.util.concurrent.TimeUnit.SECONDS);
            if (owner.completion().toCompletableFuture().isDone()) {
                throw new IllegalStateException("skill curation owner Run ended before model task");
            }
            ObjectNode input = json.createObjectNode();
            input.put("instructions", SkillPrompts.CURATION_PROMPT);
            input.put("experience", userPrompt);
            ManagedTurn active = owner;
            JsonNode output = modelTasks.executeInline(new ModelTaskRequest(
                    "skill.curation", ModelTier.LIGHT, input, List.of(), DRAFT_SCHEMA,
                    active.id(), "skill-curation", java.time.Duration.ofSeconds(STRUCTURED_TIMEOUT_SEC),
                    1, () -> Thread.currentThread().isInterrupted() || active.cancelled(), false)).output();
            SkillCurationDraft draft = parseDraft(json, output.toString());
            owner.complete(output);
            return draft;
        } catch (Exception failure) {
            log.warn("技能蒸馏调用异常: {}", failure.getMessage());
            if (owner != null) {
                try { owner.fail(failure); }
                catch (RuntimeException terminal) { log.debug("技能蒸馏 Run 已结束: {}", terminal.getMessage()); }
            }
            return null;
        } finally {
            if (owner != null) owner.close();
        }
    }

    /** Model text is accepted only as one complete schema-valid JSON object. */
    static SkillCurationDraft parseDraft(ObjectMapper json, String value) {
        java.util.Objects.requireNonNull(json, "json");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing skill curation result");
        }
        try (JsonParser parser = json.getFactory().createParser(value)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode parsed = json.readTree(parser);
            if (parsed == null || parser.nextToken() != null
                    || !SCHEMAS.validate(DRAFT_SCHEMA, parsed, "$skillCuration").isEmpty()) {
                throw new IllegalArgumentException("skill curation result violates JSON Schema");
            }
            return json.treeToValue(parsed, SkillCurationDraft.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid complete skill curation JSON", invalid);
        }
    }

    private static JsonNode draftSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        fields.putObject("worthLearning").put("type", "boolean");
        fields.putObject("action").put("type", "string")
                .putArray("enum").add("none").add("create").add("patch");
        for (String field : List.of("skillName", "reason", "description", "category", "content",
                "oldString", "newString")) {
            fields.putObject(field).put("type", "string");
        }
        fields.putObject("tags").put("type", "array")
                .putObject("items").put("type", "string");
        schema.putArray("required").add("worthLearning").add("action")
                .add("skillName").add("reason");
        SCHEMAS.requireValidSchema(schema, "skill curation result");
        return schema;
    }

    // ==================== 提示词构建 ====================

    private String buildUserPrompt(String context, List<ExecutionTrace> traces) {
        StringBuilder sb = new StringBuilder();
        sb.append(context);

        if (traces != null && !traces.isEmpty()) {
            sb.append("\n\n[执行轨迹]（共 ").append(traces.size()).append(" 次工具调用）\n");
            int idx = 0;
            for (ExecutionTrace trace : traces) {
                sb.append(++idx).append(". ").append(trace.getToolName())
                        .append(trace.isSuccess() ? " [成功]" : " [失败:" + trace.getFailureKind() + "]")
                        .append("\n");
            }
        }

        // 现有技能目录：供模型判断是 create 新技能还是 patch 既有技能
        List<Skill> enabled = skills.getEnabledSkills();
        sb.append("\n\n[现有技能目录]\n");
        if (enabled.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Skill skill : enabled) {
                sb.append("- ").append(skill.getName()).append("：").append(nz(skill.getDescription())).append("\n");
            }
        }

        // 低成功率技能：引导优先 patch 修补（使用统计反哺）
        List<String> lowSuccess = usage.lowSuccessCandidates();
        if (!lowSuccess.isEmpty()) {
            sb.append("\n[低成功率技能]（这些技能被使用后任务常失败，若本次经验与之相关，优先产 patch 修正它们）\n");
            for (String name : lowSuccess) {
                sb.append("- ").append(name).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 内部辅助 ====================

    private void toast(String title, String message) {
        try {
            UserInteractionPort port = portSupplier.get();
            if (port != null) {
                port.notify(new ToastRequest(title, message));
            }
        } catch (Exception e) {
            log.debug("技能沉淀 Toast 通知失败（忽略）: {}", e.getMessage());
        }
    }

    private static String truncate(String text) {
        return text.length() > MAX_CONTEXT_CHARS
                ? text.substring(0, MAX_CONTEXT_CHARS) + "...(截断)"
                : text;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
