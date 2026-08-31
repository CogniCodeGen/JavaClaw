package com.javaclaw.agent.automation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.BudgetExceededException;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.AutomationPlans;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;

/** Loop、Workflow 和 SDD 的有限领域策略；所有模型步骤仍由同一个 AgentLoopKernel 执行。 */
public final class AutomationExecution {
    private final CollaborationGateway collaboration;
    private final ExecutionInteractions interactions;
    private final EvaluationService evaluations;

    /** 注入协作与输入端口；缺少端口时明确暂停对应步骤，不模拟成功。 */
    public AutomationExecution(CollaborationGateway collaboration, UserInputGateway inputs) {
        this.collaboration = collaboration;
        interactions = new ExecutionInteractions(inputs);
        evaluations = new EvaluationService(interactions);
    }

    /** 主 Agent Loop 提供的单步骤入口，不负责创建 Turn 或预算账户。 */
    @FunctionalInterface
    public interface StepRunner {
        /**
         * 在原 TurnScope、工具快照和模型调用服务下执行一次有界步骤，返回已持久化最终文本。
         *
         * @param identity 稳定步骤标识，用于副作用去重
         * @param task 本步骤任务及输入
         * @param purpose 提示词用途
         * @param toolsAllowed 是否允许提出工具，规格等纯产物阶段为 false
         */
        String run(String identity, String task, PromptPurpose purpose, boolean toolsAllowed) throws Exception;
    }

    /** 执行已固化定义；每步提交检查点，暂停、取消及耗尽都不能成为 COMPLETED。 */
    public void execute(TurnExecutionContext context, ItemSink events, TurnToolSession tools, StepRunner runner)
            throws Exception {
        var attributes = context.turn().config().attributes();
        var kind = AutomationKind.valueOf(attributes.get("automationKind"));
        var plan = AutomationPlans.parse(kind, attributes.get("automationDefinition"));
        State state = new State(
                context, events, attributes.get("automationExecutionId"), attributes.get("automationDefinitionHash"));
        try {
            switch (kind) {
                case LOOP -> loop(context, events, tools, runner, plan, state);
                case WORKFLOW -> workflow(context, events, tools, plan, state);
                case SDD -> sdd(context, events, tools, runner, plan, state);
            }
        } catch (BudgetExceededException exhausted) {
            state.save("EXHAUSTED", "预算已耗尽；进展已保存，不能将当前任务标记为完成。");
            throw new ExecutionPausedException("预算已耗尽；请查看检查点及剩余工作。");
        } catch (ExecutionPausedException paused) {
            state.save("WAITING", paused.getMessage());
            throw paused;
        } catch (InterruptedException cancelled) {
            state.save("INTERRUPTED", "执行被取消；副作用凭据用于避免恢复时重复发送。");
            throw cancelled;
        }
    }

    private void loop(
            TurnExecutionContext context,
            ItemSink events,
            TurnToolSession tools,
            StepRunner runner,
            AutomationPlan plan,
            State state)
            throws Exception {
        String task = input(context);
        int stagnant = Integer.parseInt(state.outputs.getOrDefault("loop:noProgress", "0"));
        while (state.iteration < plan.limits().iterations()) {
            context.throwIfInterrupted();
            state.step = "loop-" + (state.iteration + 1);
            state.save("RUNNING", "正在执行第 " + (state.iteration + 1) + " 轮；完成必须通过实际验收。");
            if (!state.step.equals(state.outputs.get("loop:executedStep"))) {
                String result = runner.run(
                        state.step,
                        task + "\n上一轮已确认进展：\n" + state.outputs.getOrDefault("loop:result", "无") + "\n未满足条件：\n"
                                + state.outputs.getOrDefault("loop:remaining", "首次执行"),
                        PromptPurpose.LOOP_EXECUTION,
                        true);
                String fingerprint = PromptHashes.sha256(result.strip());
                stagnant = fingerprint.equals(state.outputs.get("loop:fingerprint")) ? stagnant + 1 : 0;
                state.outputs.put("loop:fingerprint", fingerprint);
                state.outputs.put("loop:result", bounded(result));
                state.outputs.put("loop:noProgress", Integer.toString(stagnant));
                state.outputs.put("loop:executedStep", state.step);
                state.save("RUNNING", "本轮执行结果已保存，正在核验验收条件。");
            }
            var evaluation = evaluations.evaluate(context, events, tools, state.step, plan.criteria());
            state.iteration++;
            state.outputs.remove("loop:executedStep");
            state.completed.add(state.step);
            state.outputs.put("loop:remaining", String.join("\n", evaluation.remaining()));
            if (evaluation.passed()) {
                state.step = "end";
                state.save("COMPLETED", "Loop 的全部验收条件已通过。");
                return;
            }
            state.save("RUNNING", evaluation.summary());
            if (stagnant >= plan.limits().noProgress()) {
                throw new ExecutionPausedException("连续多轮没有实质增量；请复核目标或提供新的条件。");
            }
        }
        throw new BudgetExceededException("Loop iteration budget exhausted");
    }

    private void workflow(
            TurnExecutionContext context, ItemSink events, TurnToolSession tools, AutomationPlan plan, State state)
            throws Exception {
        Map<String, AutomationPlan.Node> nodes = new LinkedHashMap<>();
        plan.nodes().forEach(node -> nodes.put(node.id(), node));
        if ("start".equals(state.step)) {
            state.step = plan.nodes().stream()
                    .filter(node -> node.kind() == AutomationPlan.NodeKind.START)
                    .findFirst()
                    .orElseThrow()
                    .id();
        }
        while (true) {
            context.throwIfInterrupted();
            AutomationPlan.Node node = Objects.requireNonNull(nodes.get(state.step), "checkpoint node is missing");
            if (node.kind() != AutomationPlan.NodeKind.END
                    && state.iteration >= plan.limits().iterations()) {
                throw new BudgetExceededException("Workflow step budget exhausted");
            }
            int visits = Integer.parseInt(state.outputs.getOrDefault("visit:" + node.id(), "0"));
            if (visits >= node.maxVisits()) {
                throw new BudgetExceededException("Workflow node visit limit: " + node.id());
            }
            String identity = node.id() + ":" + (visits + 1);
            state.save("RUNNING", "执行节点 " + node.id() + "（" + node.kind() + "）。");
            String next = node.next();
            String result = "";
            Map<String, String> parameters = node.parameters();
            switch (node.kind()) {
                case START -> result = input(context);
                case END -> {
                    state.save("COMPLETED", "Workflow 已到达 END；各节点的实际结果保留在 Item 中。");
                    return;
                }
                case TOOL -> {
                    var executed = tools.execute(
                            new ModelToolCall(
                                    identity,
                                    parameters.get("tool"),
                                    com.javaclaw.agent.tool.AutomationPlans.resolveArguments(
                                            parameters.getOrDefault("arguments", "{}"), state.outputs)),
                            identity);
                    events.append(executed.item());
                    if (!successful(executed.item())) {
                        throw new ExecutionPausedException("工具节点未成功：" + node.id() + "。请检查实际工具结果。");
                    }
                    result = executed.modelContent();
                }
                case AGENT -> result = child(context, events, node, identity, state);
                case CONDITION -> {
                    String value = output(state, parameters.get("input"));
                    boolean matches =
                            switch (parameters.get("operator")) {
                                case "equals" -> value.equals(parameters.get("expected"));
                                case "notEquals" -> !value.equals(parameters.get("expected"));
                                case "contains" -> value.contains(parameters.get("expected"));
                                case "isEmpty" -> value.isEmpty();
                                default -> throw new IllegalStateException("unvalidated condition operator");
                            };
                    next = matches ? node.next() : node.otherwise();
                    result = Boolean.toString(matches);
                }
                case TRANSFORM -> {
                    String value = output(state, parameters.getOrDefault("input", ""));
                    String literal = parameters.getOrDefault("value", "");
                    result = switch (parameters.get("operation")) {
                        case "constant" -> literal;
                        case "copy" -> value;
                        case "trim" -> value.strip();
                        case "uppercase" -> value.toUpperCase(Locale.ROOT);
                        case "lowercase" -> value.toLowerCase(Locale.ROOT);
                        case "append" -> value + literal;
                        default -> throw new IllegalStateException("unvalidated transform operation");
                    };
                }
                case HUMAN_INPUT -> {
                    StoredItem response =
                            interactions.ask(context, events, identity, parameters.get("prompt"), List.of());
                    result = ((ThreadItem.UserInputResponse) response.item()).value();
                }
                case OUTPUT -> {
                    result = output(state, parameters.get("input"));
                    state.artifact(new ThreadItem.Artifact(
                            state.executionId + ":" + node.id(),
                            "workflow-output",
                            parameters.get("name"),
                            visits + 1L,
                            result,
                            List.of()));
                }
            }
            state.outputs.put(node.id(), bounded(result));
            state.outputs.put("visit:" + node.id(), Integer.toString(visits + 1));
            state.completed.add(identity);
            state.iteration++;
            events.append(new ThreadItem.DynamicToolCall(
                    "workflow_step",
                    Map.of(
                            "nodeId",
                            node.id(),
                            "kind",
                            node.kind().name(),
                            "status",
                            "COMPLETED",
                            "result",
                            bounded(result))));
            state.step = next;
            state.save("RUNNING", "节点 " + node.id() + " 已完成；下一节点 " + next + "。");
        }
    }

    private String child(
            TurnExecutionContext context, ItemSink events, AutomationPlan.Node node, String identity, State state)
            throws Exception {
        if (collaboration == null) {
            throw new ExecutionPausedException("未配置协作能力，不能执行 AGENT 节点。");
        }
        String cacheKey = "child:" + identity;
        ThreadId childId;
        if (state.outputs.containsKey(cacheKey)) {
            childId = new ThreadId(state.outputs.get(cacheKey));
        } else {
            var child = collaboration.spawn(new CollaborationGateway.SpawnRequest(
                    context.thread().id(),
                    node.parameters().get("task") + "\n当前节点输入：\n"
                            + (node.parameters().containsKey("input")
                                    ? output(state, node.parameters().get("input"))
                                    : input(context)),
                    Boolean.parseBoolean(node.parameters().getOrDefault("writable", "false")),
                    node.parameters().get("profileId"),
                    state.executionId + ":" + identity));
            childId = child.id();
            state.outputs.put(cacheKey, childId.value());
            events.append(new ThreadItem.SubagentCall(childId, node.parameters().get("task"), "已创建独立子 Thread。"));
            state.save("RUNNING", "等待子 Thread " + childId.value() + "，不自动创建第二个子任务。");
        }
        while (true) {
            try {
                context.throwIfInterrupted();
                var snapshot = collaboration
                        .waitForTerminal(childId, Duration.ofMillis(250))
                        .orElseThrow();
                if (snapshot.turns().isEmpty()
                        || snapshot.turns().stream()
                                .anyMatch(turn -> !turn.status().terminal())) {
                    continue;
                }
                if (snapshot.turns().getLast().status() != TurnStatus.COMPLETED) {
                    throw new ExecutionPausedException("子任务未完成；保留其 Thread 与工作树，请显式处理后恢复。");
                }
                String summary = snapshot.items().stream()
                        .map(StoredItem::item)
                        .filter(ThreadItem.AgentMessage.class::isInstance)
                        .map(ThreadItem.AgentMessage.class::cast)
                        .reduce((left, right) -> right)
                        .map(ThreadItem.AgentMessage::text)
                        .orElse("");
                events.append(
                        new ThreadItem.SubagentCall(childId, node.parameters().get("task"), bounded(summary)));
                if (Boolean.parseBoolean(node.parameters().getOrDefault("writable", "false"))) {
                    var diff = collaboration.diff(childId);
                    events.append(new ThreadItem.DynamicToolCall(
                            "subagent_diff",
                            Map.of(
                                    "childThreadId",
                                    childId.value(),
                                    "status",
                                    diff.status(),
                                    "patchAttachmentSha256",
                                    Objects.toString(diff.patchAttachmentSha256(), ""))));
                    // 写任务只返回补丁，自动化不会借节点执行绕过父 Turn 的显式合并审批。
                }
                return summary;
            } catch (InterruptedException | BudgetExceededException interrupted) {
                collaboration.cancel(childId);
                throw interrupted;
            }
        }
    }

    private void sdd(
            TurnExecutionContext context,
            ItemSink events,
            TurnToolSession tools,
            StepRunner runner,
            AutomationPlan plan,
            State state)
            throws Exception {
        List<String> stages =
                List.of("proposal", "specification", "design", "tasks", "implementation", "verification", "archive");
        if ("start".equals(state.step)) {
            state.step = "proposal";
        }
        for (int index = stages.indexOf(state.step); index < stages.size(); index++) {
            if (index < 0) {
                throw new IllegalStateException("invalid SDD checkpoint stage");
            }
            context.throwIfInterrupted();
            String stage = stages.get(index);
            if (!"archive".equals(stage) && state.iteration >= plan.limits().iterations()) {
                throw new BudgetExceededException("SDD stage budget exhausted");
            }
            state.step = stage;
            state.save("RUNNING", "SDD 阶段：" + stage + "；审批绑定规格内容和定义哈希。");
            if ("verification".equals(stage)) {
                var evaluated =
                        evaluations.evaluate(context, events, tools, "sdd:verify:" + state.iteration, plan.criteria());
                if (!evaluated.passed()) {
                    state.outputs.put("verification:remaining", String.join("\n", evaluated.remaining()));
                    if (state.iteration >= plan.limits().iterations()) {
                        throw new BudgetExceededException("SDD rework budget exhausted");
                    }
                    state.step = "implementation";
                    index = stages.indexOf("implementation") - 1;
                    state.iteration++;
                    continue;
                }
            } else if ("archive".equals(stage)) {
                state.artifact(new ThreadItem.Artifact(
                        state.executionId + ":archive",
                        "sdd-archive",
                        "SDD 验收归档",
                        1,
                        "规格、实施和验收已经完成。\n" + state.outputs.getOrDefault("specification", ""),
                        List.of()));
            } else {
                String task = "当前 SDD 阶段：" + stage + "\n目标：" + input(context) + "\n初始规格：" + plan.specification()
                        + "\n显式导入参考文档（勾选标记不是完成或审批证据）：\n" + new java.util.TreeMap<>(plan.openSpecDocuments())
                        + "\n已确认产物：\n" + state.outputs + "\n只完成当前阶段；实现阶段以实际工具及测试证据为准。";
                String content;
                int version;
                String artifactId;
                if (Integer.toString(state.iteration).equals(state.outputs.get("prepared:" + stage))) {
                    content = state.outputs.get(stage);
                    version = Integer.parseInt(state.outputs.get("revision:" + stage));
                    artifactId = state.outputs.get("artifact:" + stage);
                } else {
                    content = runner.run(
                            "sdd:" + stage + ":" + state.iteration,
                            task,
                            PromptPurpose.SDD,
                            "implementation".equals(stage));
                    state.outputs.put(stage, bounded(content));
                    version = Integer.parseInt(state.outputs.getOrDefault("revision:" + stage, "0")) + 1;
                    state.outputs.put("revision:" + stage, Integer.toString(version));
                    StoredItem artifact = state.artifact(new ThreadItem.Artifact(
                            state.executionId + ":" + stage,
                            "sdd-" + stage,
                            "SDD " + stage,
                            version,
                            bounded(content),
                            List.of()));
                    artifactId = artifact.id().value();
                    state.outputs.put("artifact:" + stage, artifactId);
                    state.outputs.put("prepared:" + stage, Integer.toString(state.iteration));
                    state.save("RUNNING", "阶段产物已保存，不等同于已批准；暂停后复用同一版本。");
                }
                if ("specification".equals(stage) || "tasks".equals(stage)) {
                    String hash = PromptHashes.sha256(content);
                    StoredItem response = interactions.ask(
                            context,
                            events,
                            "sdd:" + stage + ":" + hash,
                            "确认采用 " + stage + " 版本 " + version + "（SHA-256 " + hash + "）？\n" + bounded(content),
                            List.of("采用", "暂停"));
                    if (!"采用".equals(((ThreadItem.UserInputResponse) response.item()).value())) {
                        throw new ExecutionPausedException("SDD 产物尚未采用，不能继续实施。");
                    }
                    state.outputs.put("approval:" + stage, state.definitionHash + ":" + hash);
                    events.append(new ThreadItem.Evaluation(
                            "sdd:" + stage + ":" + hash,
                            true,
                            "用户已确认当前产物版本。",
                            List.of(artifactId, response.id().value()),
                            List.of()));
                }
            }
            state.completed.add(stage);
            state.iteration++;
            state.step = index + 1 == stages.size() ? "end" : stages.get(index + 1);
            state.save("end".equals(state.step) ? "COMPLETED" : "RUNNING", "SDD " + stage + " 阶段已完成。");
        }
    }

    private static String input(TurnExecutionContext context) {
        return context.turn().input().stream()
                .filter(TurnInput.Text.class::isInstance)
                .map(TurnInput.Text.class::cast)
                .map(TurnInput.Text::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String output(State state, String id) {
        if (id.isEmpty()) {
            return "";
        }
        if (!state.outputs.containsKey(id)) {
            throw new IllegalStateException("Workflow input is not available: " + id);
        }
        return state.outputs.get(id);
    }

    private static boolean successful(ThreadItem item) {
        return switch (item) {
            case ThreadItem.CommandExecution command -> !command.timedOut() && command.exitCode() == 0;
            case ThreadItem.ErrorItem ignored -> false;
            case ThreadItem.DynamicToolCall result ->
                !List.of("denied", "error", "failed", "unknown")
                        .contains(result.result().getOrDefault("status", "").toLowerCase(Locale.ROOT));
            case ThreadItem.McpToolCall result ->
                "completed".equals(result.result().get("status"));
            default -> true;
        };
    }

    private static String bounded(String value) {
        if (value.length() > 16_384) {
            throw new IllegalStateException("automation step output exceeds 16384 characters; use an attachment");
        }
        return value;
    }

    private static final class State {
        private final TurnExecutionContext context;
        private final ItemSink events;
        private final String executionId;
        private final String definitionHash;
        private final long started = System.nanoTime();
        private final Map<String, String> outputs = new LinkedHashMap<>();
        private final List<String> completed = new ArrayList<>();
        private int iteration;
        private String step = "start";
        private int previousCalls;
        private long previousTokens;
        private long previousMillis;

        private State(TurnExecutionContext context, ItemSink events, String executionId, String hash) {
            this.context = context;
            this.events = events;
            this.executionId = Objects.requireNonNull(executionId);
            definitionHash = Objects.requireNonNull(hash);
            if ("true".equals(context.turn().config().attributes().get("automationResume"))) {
                var checkpoint = context.priorItems().stream()
                        .map(StoredItem::item)
                        .filter(ThreadItem.Checkpoint.class::isInstance)
                        .map(ThreadItem.Checkpoint.class::cast)
                        .filter(value -> executionId.equals(value.executionId()))
                        .reduce((left, right) -> right)
                        .orElseThrow();
                if (!definitionHash.equals(checkpoint.definitionHash()) || "COMPLETED".equals(checkpoint.status())) {
                    throw new IllegalStateException("checkpoint is complete or its definition has changed");
                }
                step = checkpoint.stepId();
                iteration = checkpoint.iteration();
                outputs.putAll(checkpoint.outputs());
                completed.addAll(checkpoint.completedSteps());
                previousCalls = Math.max(
                        checkpoint.usedModelCalls(),
                        Integer.parseInt(
                                context.turn().config().attributes().getOrDefault("automationUsedCalls", "0")));
                previousTokens = Math.max(
                        checkpoint.usedTokens(),
                        Long.parseLong(context.turn().config().attributes().getOrDefault("automationUsedTokens", "0")));
                previousMillis = Math.max(
                        checkpoint.elapsedMillis(),
                        Long.parseLong(
                                context.turn().config().attributes().getOrDefault("automationElapsedMillis", "0")));
                int lastCheckpoint = -1;
                for (int index = 0; index < context.priorItems().size(); index++) {
                    if (context.priorItems().get(index).item() == checkpoint) {
                        lastCheckpoint = index;
                    }
                }
                for (var stored : context.priorItems()
                        .subList(lastCheckpoint + 1, context.priorItems().size())) {
                    if (stored.item() instanceof ThreadItem.Artifact artifact
                            && artifact.artifactId().equals(executionId + ":" + step)
                            && artifact.category().equals("sdd-" + step)) {
                        // 产物提交与后继检查点之间崩溃时，从不可变 Item 补齐恢复状态，不再次调用模型改写待审版本。
                        outputs.put(step, artifact.content());
                        outputs.put("revision:" + step, Long.toString(artifact.revision()));
                        outputs.put("artifact:" + step, stored.id().value());
                        outputs.put("prepared:" + step, Integer.toString(iteration));
                    }
                }
            }
        }

        private void save(String status, String summary) {
            events.append(new ThreadItem.Checkpoint(
                    executionId,
                    definitionHash,
                    step,
                    status,
                    iteration,
                    outputs,
                    completed,
                    previousCalls + context.scope().budget().usedCalls(),
                    previousTokens + context.scope().budget().usedTokens(),
                    previousMillis
                            + Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    summary));
        }

        private StoredItem artifact(ThreadItem.Artifact value) {
            // 崩溃可能发生在 Artifact 提交后、下一检查点之前；保留原版本，不能重插同一业务产物。
            var previous = context.priorItems().stream()
                    .filter(item -> item.item() instanceof ThreadItem.Artifact artifact
                            && artifact.artifactId().equals(value.artifactId())
                            && artifact.revision() == value.revision())
                    .findFirst();
            if (previous.isPresent()) {
                if (!previous.get().item().equals(value)) {
                    throw new IllegalStateException("artifact revision already contains different content");
                }
                return previous.get();
            }
            return events.append(value);
        }
    }
}
