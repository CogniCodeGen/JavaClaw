package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.application.agent.FrameworkToolApprovalCoordinator;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.prompt.SddPrompts;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.task.sdd.SddAgents;
import com.javaclaw.task.sdd.SddTokenSink;
import com.javaclaw.task.sdd.SddWorkNotes;
import com.javaclaw.task.sdd.TaskContext;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Requirement;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.TaskItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * SDD model behavior implemented as child Runs of the one AgentEngine. Structured phases reuse
 * Spring AI's {@link BeanOutputConverter}; implementation phases use the same run-scoped tool
 * gateway as Chat, Loop and Workflow.
 */
public final class FrameworkSddAgents implements SddAgents, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FrameworkSddAgents.class);
    public static final String SPLIT_SENTINEL = "[需要拆解]";

    private final AgentClient agents;
    private final WorkspaceContext workspace;
    private final AgentConfig settings;
    private final SkillManager skills;
    private final SddTokenSink tokens;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final Set<RunId> activeRuns = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile RunId lastRunId;
    private volatile long structuredTimeoutSec = 120;
    private volatile long execTimeoutSec = 300;
    private volatile int execMaxIters = 12;
    private volatile boolean closed;

    public FrameworkSddAgents(
            AgentClient agents,
            WorkspaceContext workspace,
            AgentConfig settings,
            SkillRuntimeServices skillRuntime,
            SddTokenSink tokenSink,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.skills = Objects.requireNonNull(skillRuntime, "skillRuntime").manager();
        this.tokens = tokenSink == null ? SddTokenSink.NOOP : tokenSink;
        this.json = Objects.requireNonNull(json, "json");
    }

    public FrameworkSddAgents structuredTimeoutSec(long seconds) {
        if (seconds > 0) structuredTimeoutSec = seconds;
        return this;
    }

    public FrameworkSddAgents execTimeoutSec(long seconds) {
        if (seconds > 0) execTimeoutSec = seconds;
        return this;
    }

    public FrameworkSddAgents execMaxIters(int value) {
        if (value > 0) execMaxIters = value;
        return this;
    }

    public RunId lastRunId() {
        return lastRunId;
    }

    public boolean cancelled() {
        return closed;
    }

    @Override
    public Proposal clarifyAndPropose(TaskContext ctx, String feedback) {
        String system = withSkills(SddPrompts.PROPOSE_SYS_PROMPT, "需求澄清", "规格驱动开发");
        String user = "用户需求：\n" + ctx.description()
                + (blank(feedback) ? "" : "\n\n上一轮评审驳回意见（请据此修正）：\n" + feedback);
        SddDrafts.ProposalDraft draft = structured(ctx, "proposal", system, user,
                SddDrafts.ProposalDraft.class);
        return draft == null
                ? new Proposal(ctx.description(), "（提案产出失败，按原始需求处理）", "")
                : new Proposal(nz(draft.why), nz(draft.whatChanges), nz(draft.outOfScope));
    }

    @Override
    public List<Capability> specify(TaskContext ctx, Proposal proposal) {
        String user = "提案：\n为什么：" + proposal.why() + "\n改什么：" + proposal.whatChanges()
                + "\n不改什么：" + nz(proposal.outOfScope()) + "\n\n用户原始需求：\n" + ctx.description();
        SddDrafts.SpecDraft draft = structured(ctx, "spec",
                withSkills(SddPrompts.SPECIFY_SYS_PROMPT, "规格驱动开发"), user,
                SddDrafts.SpecDraft.class);
        if (draft == null || draft.capabilities == null) return List.of();
        List<Capability> capabilities = new ArrayList<>();
        for (SddDrafts.CapabilityDraft capability : draft.capabilities) {
            if (capability == null || blank(capability.name)) continue;
            List<Requirement> requirements = new ArrayList<>();
            if (capability.requirements != null) {
                for (SddDrafts.RequirementDraft requirement : capability.requirements) {
                    if (requirement == null || blank(requirement.title)) continue;
                    List<Scenario> scenarios = new ArrayList<>();
                    if (requirement.scenarios != null) {
                        for (SddDrafts.ScenarioDraft scenario : requirement.scenarios) {
                            if (scenario == null || blank(scenario.title)) continue;
                            scenarios.add(new Scenario(scenario.title, nz(scenario.given),
                                    nz(scenario.when), nz(scenario.then),
                                    new Criterion(scenario.criterionType, scenario.criterionPredicate)));
                        }
                    }
                    requirements.add(new Requirement(requirement.title, scenarios));
                }
            }
            capabilities.add(new Capability(capability.name, requirements));
        }
        return capabilities;
    }

    @Override
    public String design(TaskContext ctx, Proposal proposal, List<Capability> capabilities) {
        String text = invoke(ctx, "design",
                withSkills(SddPrompts.DESIGN_SYS_PROMPT, "规格驱动开发"),
                "需求：" + ctx.description() + "\n能力数：" + capabilities.size(),
                structuredTimeoutSec, false);
        return blank(text) || text.contains("无需设计") ? null : text;
    }

    @Override
    public List<TaskItem> planTasks(TaskContext ctx, Proposal proposal, List<Capability> capabilities,
                                    String design, String feedback) {
        String user = "提案改什么：" + proposal.whatChanges()
                + "\n能力规格场景数：" + capabilities.stream()
                .mapToInt(capability -> capability.allScenarios().size()).sum()
                + (design == null ? "" : "\n设计要点：见 design.md")
                + (blank(feedback) ? "" : "\n\n需据以下反馈调整/补做：\n" + feedback)
                + "\n\n工作目录：" + ctx.workDir();
        SddDrafts.TaskPlanDraft draft = structured(ctx, "plan",
                withSkills(SddPrompts.PLAN_TASKS_SYS_PROMPT, "规格驱动开发"), user,
                SddDrafts.TaskPlanDraft.class);
        if (draft == null || draft.tasks == null) return List.of();
        List<TaskItem> result = new ArrayList<>();
        int index = 1;
        for (SddDrafts.TaskItemDraft task : draft.tasks) {
            if (task == null || blank(task.action) || isMetaTask(task)) continue;
            result.add(new TaskItem(index++, task.action,
                    task.files == null ? List.of() : task.files, task.criterion, false));
        }
        return result;
    }

    @Override
    public ExecutionResult executeTask(TaskContext ctx, TaskItem current, List<TaskItem> doneItems,
                                       List<Capability> specs) {
        String system = withSkillsCompact(
                withSkills(SddPrompts.executeTaskSysPrompt(SPLIT_SENTINEL)),
                "测试驱动开发", "系统化调试", "代码评审");
        StringBuilder user = new StringBuilder()
                .append("当前实现项 #").append(current.index()).append("：")
                .append(current.action()).append('\n');
        if (current.files() != null && !current.files().isEmpty()) {
            user.append("涉及文件：").append(String.join(", ", current.files())).append('\n');
        }
        if (!blank(current.criterion())) user.append("完成判据：").append(current.criterion()).append('\n');
        user.append("工作目录：").append(ctx.workDir()).append('\n');
        String ledger = SddWorkNotes.readLedgerTail(ctx.workDir(), 10);
        if (!ledger.isBlank()) user.append("\n【已完成项进度账本】\n").append(ledger).append('\n');
        String projectMap = SddWorkNotes.ensureProjectMap(ctx.workDir());
        if (!projectMap.isBlank()) user.append("\n【项目文件清单】\n").append(projectMap).append('\n');

        String text = invoke(ctx, "implement", system, user.toString(), execTimeoutSec, true);
        List<String> split = parseSplit(text);
        if (!split.isEmpty()) return ExecutionResult.split(split);
        SddWorkNotes.appendLedger(ctx.workDir(), "#" + current.index() + " " + current.action()
                + " — " + SddWorkNotes.oneLine(text, 120));
        return ExecutionResult.done(blank(text) ? "（执行体未输出摘要）" : text);
    }

    @Override
    public List<String> remediate(TaskContext ctx, List<Scenario> failedScenarios, OpenSpecChange change) {
        String failures = failedScenarios.stream()
                .map(scenario -> "- " + scenario.title() + "（判据：" + scenario.criterion() + "）")
                .collect(Collectors.joining("\n"));
        SddDrafts.RemediationDraft draft = structured(ctx, "remediate",
                withSkills(SddPrompts.REMEDIATE_SYS_PROMPT, "系统化调试"),
                "以下验收场景未通过，请给出补做项：\n" + failures
                        + "\n\n工作目录：" + ctx.workDir(),
                SddDrafts.RemediationDraft.class);
        if (draft == null || draft.fixes == null) return List.of();
        return draft.fixes.stream().filter(value -> !blank(value))
                .filter(value -> !isMetaTaskText(value)).toList();
    }

    private <T> T structured(
            TaskContext ctx, String phase, String system, String user, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String output = invoke(ctx, phase,
                system + "\n\n" + converter.getFormat(), user, structuredTimeoutSec, false);
        if (blank(output)) return null;
        try {
            return converter.convert(stripFence(output));
        } catch (RuntimeException failure) {
            log.warn("[SDD] {} 阶段结构化输出解析失败: {}", phase, failure.toString());
            try {
                return json.readValue(stripFence(output), type);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String invoke(
            TaskContext ctx, String phase, String system, String user,
            long timeoutSeconds, boolean toolsEnabled) {
        if (closed) throw new IllegalStateException("SDD agent adapter is closed");
        int call = sequence.incrementAndGet();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        ObjectNode systemNode = JsonNodeFactory.instance.objectNode();
        Map<String, JsonNode> attributes = new java.util.LinkedHashMap<>();
        attributes.put("framework.systemPrompt", systemNode.textNode(system));
        attributes.put("framework.disableTools", JsonNodeFactory.instance.booleanNode(!toolsEnabled));
        if (!blank(ctx.workDir())) attributes.put("workDir", systemNode.textNode(ctx.workDir()));
        attributes.put("sdd.phase", systemNode.textNode(phase));
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("sdd"))
                .source(InvocationSource.sdd(ctx.id()))
                .scope(new RunScope(workspace.workspaceId(), "local-user", "sdd:" + ctx.id()))
                .input(InputBlock.text(user))
                .linkage(new RunLinkage(null, ctx.id(), ctx.id()))
                .permissionCeiling(toolsEnabled ? PermissionSet.UNRESTRICTED : PermissionSet.NONE)
                .budget(new RunBudget(timeout, Long.MAX_VALUE, Long.MAX_VALUE,
                        Math.max(1, execMaxIters * 8), new BigDecimal("1E+100")))
                .idempotencyKey("sdd:" + ctx.id() + ":" + phase + ":" + call)
                .attributes(attributes)
                .build();
        RunHandle handle = agents.start(request);
        lastRunId = handle.id();
        activeRuns.add(handle.id());
        Usage usage = new Usage();
        Disposable events = handle.events(0).subscribe(event -> onEvent(ctx, handle, usage, event));
        try {
            RunOutcome outcome = handle.completion().toCompletableFuture()
                    .get(timeout.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            if (outcome.state() != RunState.COMPLETED) {
                throw new IllegalStateException("SDD " + phase + " run " + outcome.state()
                        + ": " + outcome.error());
            }
            JsonNode output = outcome.output();
            if (output != null) {
                usage.input = output.path("usage").path("inputTokens").asLong(usage.input);
                usage.output = output.path("usage").path("outputTokens").asLong(usage.output);
            }
            tokens.record(phase, usage.input, usage.output);
            if (output == null) return "";
            String text = output.path("text").asText("");
            return text.isBlank() ? output.path("value").asText("") : text;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            agents.cancel(handle.id(), new CancelReason("SDD_INTERRUPTED", phase));
            throw new IllegalStateException("SDD phase interrupted", interrupted);
        } catch (Exception failure) {
            agents.cancel(handle.id(), new CancelReason("SDD_PHASE_FAILED", failure.toString()));
            throw new IllegalStateException("SDD phase failed: " + phase, failure);
        } finally {
            events.dispose();
            activeRuns.remove(handle.id());
        }
    }

    private void onEvent(TaskContext ctx, RunHandle handle, Usage usage, RunEventEnvelope event) {
        JsonNode payload = event.payload();
        if (event.type().equals("core.model.completed")) {
            usage.input = payload.path("inputTokens").asLong(usage.input);
            usage.output = payload.path("outputTokens").asLong(usage.output);
        } else if (event.type().equals("core.run.waiting_approval")) {
            java.util.concurrent.CompletableFuture.runAsync(() -> approve(ctx, handle, payload));
        } else if (event.type().equals("core.run.waiting_input")) {
            agents.cancel(handle.id(), new CancelReason(
                    "SDD_UNEXPECTED_INPUT", "SDD child run cannot await input"));
        }
    }

    private void approve(TaskContext ctx, RunHandle handle, JsonNode payload) {
        ToolCallOrigin origin = ToolCallOrigin.managedTask(ctx.id(), ctx.workDir());
        FrameworkToolApprovalCoordinator.resolve(agents, handle, origin, payload);
    }

    static List<String> parseSplit(String text) {
        if (text == null) return List.of();
        for (String line : text.split("\n")) {
            String value = line.strip();
            int position = value.indexOf(SPLIT_SENTINEL);
            if (position < 0) continue;
            String rest = value.substring(position + SPLIT_SENTINEL.length())
                    .replaceFirst("^[：:\\s]+", "");
            List<String> parts = new ArrayList<>();
            for (String part : rest.split("[；;]")) if (!part.isBlank()) parts.add(part.strip());
            if (!parts.isEmpty()) return parts;
        }
        return List.of();
    }

    private static boolean isMetaTask(SddDrafts.TaskItemDraft task) {
        StringBuilder text = new StringBuilder(nz(task.action));
        if (task.files != null) task.files.forEach(file -> text.append(' ').append(file));
        if (task.criterion != null) text.append(' ').append(task.criterion);
        return isMetaTaskText(text.toString());
    }

    private static boolean isMetaTaskText(String text) {
        if (text.contains(".agent/openspec") || text.contains("openspec/changes")) return true;
        if (text.contains("proposal.md") || text.contains("design.md")
                || text.contains("tasks.md") || text.contains("spec.md")) return true;
        return text.contains("向用户展示") || text.contains("获取用户确认")
                || text.contains("等待用户确认") || text.contains("征得用户")
                || text.contains("用户书面") || text.contains("获取书面确认");
    }

    private String withSkills(String base, String... names) {
        StringBuilder result = new StringBuilder(base);
        for (String name : names) {
            try {
                String detail = skills.buildSkillDetail(name);
                if (!blank(detail)) result.append("\n\n").append(detail);
            } catch (Exception failure) {
                log.debug("[SDD] 技能详情不可用: {}", name, failure);
            }
        }
        return result.toString();
    }

    private String withSkillsCompact(String base, String... names) {
        StringBuilder catalog = new StringBuilder();
        for (String name : names) {
            try {
                var skill = skills.getSkillByName(name);
                if (skill != null) catalog.append("\n- ").append(skill.getName())
                        .append(blank(skill.getDescription()) ? "" : "：" + skill.getDescription().strip());
            } catch (Exception failure) {
                log.debug("[SDD] 技能目录项不可用: {}", name, failure);
            }
        }
        return catalog.isEmpty() ? base : base + "\n\n【可用技能】" + catalog
                + "\n需要完整步骤时使用 skill_read。";
    }

    private static String stripFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) return text;
        int start = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        return start >= 0 && end > start ? text.substring(start + 1, end).trim() : text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        closed = true;
        List.copyOf(activeRuns).forEach(runId ->
                agents.cancel(runId, new CancelReason("SDD_CANCELLED", "SDD workflow stopped")));
        activeRuns.clear();
    }

    private static final class Usage {
        private volatile long input;
        private volatile long output;
    }
}
