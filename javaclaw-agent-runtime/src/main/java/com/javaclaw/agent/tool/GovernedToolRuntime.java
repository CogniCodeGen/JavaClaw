package com.javaclaw.agent.tool;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Schema -> policy -> risk -> pre-hook -> approval -> sandbox -> redaction -> post-hook. */
public final class GovernedToolRuntime implements TurnToolSessionFactory {
    private static final int MAX_ARGUMENT_CHARS = 1_000_000;

    private final CompositeToolProvider providers;
    private final List<ToolHooks.PreToolHook> preHooks;
    private final List<ToolHooks.PostToolHook> postHooks;
    private final ApprovalGateway approvals;
    private final ToolAuthorizationGateway authorizations;
    private final SandboxExecutor sandbox;
    private final SandboxPolicy serverCeiling;
    private final Duration hookTimeout;
    private final ObjectMapper json;
    private final SecretRedactor redactor;
    private final ThreadPoolExecutor postExecutor;

    /** 装配统一工具治理链与有界 Post Hook 执行器；null 审批网关按拒绝处理，null Hook 超时使用 2 秒，close 释放执行器。 */
    public GovernedToolRuntime(
            List<ToolProvider> providers,
            List<ToolHooks.PreToolHook> preHooks,
            List<ToolHooks.PostToolHook> postHooks,
            ApprovalGateway approvals,
            SandboxExecutor sandbox,
            SandboxPolicy serverCeiling,
            Duration hookTimeout,
            ObjectMapper json) {
        this(
                providers,
                preHooks,
                postHooks,
                approvals,
                sandbox,
                serverCeiling,
                hookTimeout,
                json,
                ToolAuthorizationGateway.DENY_ALL);
    }

    /** 装配有范围的无人值守授权；缺失网关仍按拒绝处理，授权不能改变传入的沙箱策略。 */
    public GovernedToolRuntime(
            List<ToolProvider> providers,
            List<ToolHooks.PreToolHook> preHooks,
            List<ToolHooks.PostToolHook> postHooks,
            ApprovalGateway approvals,
            SandboxExecutor sandbox,
            SandboxPolicy serverCeiling,
            Duration hookTimeout,
            ObjectMapper json,
            ToolAuthorizationGateway authorizations) {
        this.json = Objects.requireNonNull(json, "json");
        this.providers = new CompositeToolProvider(providers);
        this.preHooks = preHooks == null ? List.of() : List.copyOf(preHooks);
        this.postHooks = postHooks == null ? List.of() : List.copyOf(postHooks);
        this.approvals = approvals == null ? ApprovalGateway.DENY_ALL : approvals;
        this.authorizations = authorizations == null ? ToolAuthorizationGateway.DENY_ALL : authorizations;
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.serverCeiling = Objects.requireNonNull(serverCeiling, "serverCeiling");
        this.hookTimeout = hookTimeout == null ? Duration.ofSeconds(2) : hookTimeout;
        if (this.hookTimeout.isNegative() || this.hookTimeout.isZero()) {
            throw new IllegalArgumentException("hook timeout must be positive");
        }
        redactor = new SecretRedactor(System.getenv());
        postExecutor = new ThreadPoolExecutor(
                1,
                2,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                Thread.ofPlatform().name("javaclaw-post-hook-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public TurnToolSession open(TurnExecutionContext context, ItemSink events) {
        Objects.requireNonNull(context, "context");
        ItemSink sink = events == null ? ignored -> null : events;
        CompositeToolProvider.Snapshot snapshot = providers.snapshot(context);
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        snapshot.failures()
                .forEach(failure -> sink.append(new ThreadItem.ErrorItem(
                        "tool_provider_unavailable",
                        "Tool provider " + failure.providerId() + " is unavailable: "
                                + redactor.text(failure.message()),
                        true)));
        for (CompositeToolProvider.Contribution contribution : snapshot.contributions()) {
            for (RegisteredTool tool : contribution.tools()) {
                if ("PLAN".equals(context.turn().config().attributes().get("profileKind"))
                        && tool.effect() != ToolEffect.READ_ONLY) {
                    // 只读沙箱不能约束远程业务写入，因此 PLAN 同时拒绝所有未经确认的业务效果。
                    continue;
                }
                Entry entry = new Entry(
                        tool, new BasicJsonSchema(json, tool.descriptor().inputSchemaJson()));
                Entry previous = entries.putIfAbsent(tool.descriptor().name(), entry);
                if (previous != null) {
                    sink.append(new ThreadItem.ErrorItem(
                            "duplicate_tool",
                            "Ignored duplicate tool " + tool.descriptor().name() + " from provider "
                                    + contribution.providerId(),
                            false));
                }
            }
        }
        return new Session(context, sink, Map.copyOf(entries), snapshot);
    }

    private ToolExecutionResult execute(
            ToolExecutionContext context,
            ItemSink events,
            Map<String, Entry> tools,
            String stepIdentity,
            Map<String, ThreadItem.EffectReceipt> receipts) {
        Entry entry = tools.get(context.call().name());
        if (entry == null) {
            return failure(context.call().name(), "tool is not registered");
        }
        if (!context.config().enabledTools().isEmpty()
                && !context.config().enabledTools().contains(context.call().name())) {
            return failure(context.call().name(), "tool is disabled for this turn");
        }
        String receiptKey = null;
        try {
            context.scope().check();
            // 快照只固定可见目录，不延长来源的授权期限；执行时仍需检查禁用、隔离与 revision 变化。
            entry.tool().availability().verify();
            JsonNode arguments = parseArguments(context.call().argumentsJson());
            entry.schema().validate(arguments);
            // Turn、工具来源和服务端上限始终求交集；Hook 后的策略也只能收窄，审批不能绕开执行沙箱。
            SandboxPolicy effective = context.config()
                    .sandboxPolicy()
                    .intersect(entry.tool().sandboxCeiling())
                    .intersect(serverCeiling);
            ToolHooks.Invocation invocation = new ToolHooks.Invocation(
                    context, entry.tool().origin(), entry.tool().risk(), arguments, effective);
            for (ToolHooks.PreToolHook hook : preHooks) {
                ToolHooks.Invocation currentInvocation = invocation;
                ToolHooks.PreToolDecision decision = timed(() -> hook.apply(currentInvocation));
                if (!decision.allowed()) {
                    return failure(context.call().name(), "pre-tool hook denied execution: " + decision.reason());
                }
                arguments = decision.arguments();
                entry.schema().validate(arguments);
                effective = effective.intersect(decision.policy());
                invocation = new ToolHooks.Invocation(
                        context, entry.tool().origin(), entry.tool().risk(), arguments, effective);
            }
            if (entry.tool().effect() != ToolEffect.READ_ONLY) {
                String execution = context.config()
                        .attributes()
                        .getOrDefault(
                                "automationExecutionId", context.turn().id().value());
                receiptKey = com.javaclaw.agent.prompt.PromptHashes.sha256(
                        execution + "\n" + stepIdentity + "\n" + context.call().name() + "\n" + canonical(arguments));
                ThreadItem.EffectReceipt prior = receipts.get(receiptKey);
                if (prior != null) {
                    if ("CONFIRMED".equals(prior.state())) {
                        return prior.result();
                    }
                    return failure(
                            context.call().name(),
                            "previous effect outcome is UNKNOWN; query or obtain explicit confirmation before a new operation");
                }
            }
            // 已确认的恢复结果先返回，不再次询问用户或扣除额度；UNKNOWN/PENDING 永远不进入重新执行。
            String approvalFailure = requireApproval(
                    entry.tool(),
                    context,
                    arguments,
                    effective,
                    events,
                    receiptKey == null
                            ? context.turn().id().value() + ":" + context.call().id()
                            : receiptKey);
            if (approvalFailure != null) {
                return failure(context.call().name(), approvalFailure);
            }
            context.scope().check();
            entry.tool().availability().verify();
            if (receiptKey != null) {
                var pending = new ThreadItem.EffectReceipt(
                        receiptKey, context.call().name(), "PENDING", null, "操作已登记；结果确认前不能自动重发。");
                events.append(pending);
                receipts.put(receiptKey, pending);
            }
            ToolHandler.Result handled = entry.tool()
                    .handler()
                    .execute(new ToolHandler.Context(context, arguments, effective, sandbox, events));
            ToolExecutionResult result =
                    new ToolExecutionResult(redactor.item(handled.item()), redactor.text(handled.modelContent()));
            if (receiptKey != null) {
                var confirmed = new ThreadItem.EffectReceipt(
                        receiptKey, context.call().name(), "CONFIRMED", result, "已保存实际工具返回结果；不推断外部送达或完成状态。");
                events.append(confirmed);
                receipts.put(receiptKey, confirmed);
            }
            dispatchPostHooks(invocation, result, events);
            return result;
        } catch (Exception failure) {
            if (receiptKey != null && receipts.containsKey(receiptKey)) {
                var unknown = new ThreadItem.EffectReceipt(
                        receiptKey, context.call().name(), "UNKNOWN", null, "执行中断或结果无法确认；禁止自动重发。");
                receipts.put(receiptKey, unknown);
                try {
                    events.append(unknown);
                } catch (RuntimeException unavailable) {
                    // PENDING 已提交时同样表示结果未知；数据库故障不能成为重新执行的授权。
                }
            }
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return failure(context.call().name(), message);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var sorted = json.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value;
    }

    private String requireApproval(
            RegisteredTool tool,
            ToolExecutionContext context,
            JsonNode arguments,
            SandboxPolicy policy,
            com.javaclaw.agent.runtime.ItemSink events,
            String invocationId)
            throws Exception {
        boolean inherentlySensitive = tool.requestsApproval()
                || tool.risk().ordinal() >= ToolRisk.HIGH.ordinal()
                || policy.mode() == SandboxMode.HOST_FULL_ACCESS
                || policy.network().mode() != NetworkPolicy.Mode.DISABLED;
        ApprovalPolicy setting = context.config().approvalPolicy();
        boolean prompt =
                switch (setting) {
                    case NEVER -> false;
                    case ON_REQUEST -> tool.requestsApproval() || policy.mode() == SandboxMode.HOST_FULL_ACCESS;
                    case ON_RISK -> inherentlySensitive;
                    case ALWAYS -> true;
                };
        if (setting == ApprovalPolicy.NEVER && inherentlySensitive) {
            if (tool.origin() == ToolOrigin.MCP
                    && "SCHEDULE".equals(context.config().attributes().get("profileKind"))
                    && policy.mode() != SandboxMode.HOST_FULL_ACCESS
                    && policy.network().mode() != NetworkPolicy.Mode.FULL) {
                var receipt = authorizations.consume(context, tool, arguments, policy, invocationId);
                if (receipt.isPresent()) {
                    var consumed = receipt.get();
                    events.append(new ThreadItem.DynamicToolCall(
                            "tool_preauthorization",
                            Map.of(
                                    "authorizationId", consumed.authorizationId(),
                                    "revision", Long.toString(consumed.revision()),
                                    "remainingUses", Integer.toString(consumed.remainingUses()))));
                    return null;
                }
            }
            return "approval is required but disabled by turn policy";
        }
        if (!prompt) {
            return null;
        }
        String id = "approval_" + UUID.randomUUID().toString().replace("-", "");
        ApprovalGateway.Request request =
                new ApprovalGateway.Request(id, context, tool.origin(), tool.risk(), arguments, policy);
        boolean approved = approvals.approve(
                request,
                () -> events.append(new ThreadItem.ApprovalRequest(
                        id,
                        "Allow " + tool.descriptor().name() + " from " + tool.origin(),
                        tool.risk().name())));
        events.approvalResolved(id, approved);
        return approved ? null : "user approval was denied";
    }

    private JsonNode parseArguments(String value) throws JsonProcessingException {
        if (value.length() > MAX_ARGUMENT_CHARS) {
            throw new IllegalArgumentException("tool arguments exceed size limit");
        }
        JsonNode parsed = json.readTree(value);
        if (parsed == null) {
            throw new IllegalArgumentException("tool arguments are empty");
        }
        return parsed;
    }

    private <T> T timed(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Thread thread = Thread.ofVirtual().name("javaclaw-pre-hook").start(task);
        try {
            return task.get(hookTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(true);
            thread.interrupt();
            throw new IllegalStateException("pre-tool hook timed out");
        }
    }

    private void dispatchPostHooks(
            ToolHooks.Invocation invocation, ToolExecutionResult result, com.javaclaw.agent.runtime.ItemSink events) {
        postHooks.forEach(hook -> {
            try {
                postExecutor.execute(() -> {
                    try {
                        hook.accept(invocation, result);
                    } catch (Exception failure) {
                        recordPostHookFailure(events, failure);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                recordPostHookFailure(events, new IllegalStateException("post-tool hook queue is full"));
            }
        });
    }

    private void recordPostHookFailure(com.javaclaw.agent.runtime.ItemSink events, Exception failure) {
        try {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            events.append(new ThreadItem.ErrorItem("post_hook_failed", redactor.text(message), false));
        } catch (Exception ignored) {
            // Audit hooks fail open. Failure to append diagnostics cannot affect tool output.
        }
    }

    private ToolExecutionResult failure(String tool, String reason) {
        String safe = redactor.text(reason);
        return new ToolExecutionResult(
                new ThreadItem.DynamicToolCall(tool, Map.of("status", "denied", "reason", safe)),
                "Tool error: " + safe);
    }

    @Override
    public void close() {
        postExecutor.shutdownNow();
    }

    private final class Session implements TurnToolSession {
        private final TurnExecutionContext context;
        private final ItemSink events;
        private final Map<String, Entry> tools;
        private final CompositeToolProvider.Snapshot snapshot;
        private final Map<String, ThreadItem.EffectReceipt> receipts = new LinkedHashMap<>();

        private Session(
                TurnExecutionContext context,
                ItemSink events,
                Map<String, Entry> tools,
                CompositeToolProvider.Snapshot snapshot) {
            this.context = context;
            this.events = events;
            this.tools = tools;
            this.snapshot = snapshot;
            context.priorItems().stream()
                    .map(com.javaclaw.core.api.StoredItem::item)
                    .filter(ThreadItem.EffectReceipt.class::isInstance)
                    .map(ThreadItem.EffectReceipt.class::cast)
                    .forEach(receipt -> receipts.put(receipt.key(), receipt));
        }

        @Override
        public List<ToolDescriptor> availableTools() {
            return tools.values().stream()
                    .map(Entry::tool)
                    .filter(tool -> context.turn().config().enabledTools().isEmpty()
                            || context.turn()
                                    .config()
                                    .enabledTools()
                                    .contains(tool.descriptor().name()))
                    .map(RegisteredTool::descriptor)
                    .sorted(Comparator.comparing(ToolDescriptor::name))
                    .toList();
        }

        @Override
        public ToolExecutionResult execute(ModelToolCall call) {
            return execute(call, call.id());
        }

        @Override
        public synchronized ToolExecutionResult execute(ModelToolCall call, String stepIdentity) {
            return GovernedToolRuntime.this.execute(
                    new ToolExecutionContext(
                            context.thread(),
                            context.turn(),
                            call,
                            context.turn().config(),
                            context.scope()),
                    events,
                    tools,
                    Objects.requireNonNull(stepIdentity, "stepIdentity"),
                    receipts);
        }

        @Override
        public void close() {
            snapshot.close();
        }
    }

    private record Entry(RegisteredTool tool, BasicJsonSchema schema) {}
}
