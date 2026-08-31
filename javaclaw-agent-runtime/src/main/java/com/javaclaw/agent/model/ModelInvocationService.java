package com.javaclaw.agent.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.agent.prompt.AgentsInstructionResolution;
import com.javaclaw.agent.prompt.ContextBlock;
import com.javaclaw.agent.prompt.PromptCatalog;
import com.javaclaw.agent.prompt.PromptCompiler;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.prompt.PromptSnapshot;
import com.javaclaw.agent.runtime.BudgetExceededException;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;

/** 所有对话与辅助模型调用共用的提示词、预算、取消和审计边界；本类不执行模型提出的工具。 */
public final class ModelInvocationService {
    private final ModelGateway gateway;
    private final PromptCompiler compiler;

    /** 装配真实或假模型网关，使用同一随包发布的 PromptCatalog。 */
    public ModelInvocationService(ModelGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.compiler = new PromptCompiler(new PromptCatalog());
    }

    /** 为一次用途编译固定模板、来源和工具快照；纯编译不会消费模型预算。 */
    public PromptCompiler.CompiledPrompt prepare(
            PromptPurpose purpose,
            TurnConfig config,
            List<ToolDescriptor> tools,
            List<ContextBlock> references,
            AgentsInstructionResolution instructions,
            List<ModelMessage> dialogue) {
        return compiler.compile(purpose, config, tools, references, instructions, dialogue);
    }

    /** 追加版本化输出契约并记录 Schema 哈希；真正的校验仍由 contract.decode 执行。 */
    public PromptCompiler.CompiledPrompt withContract(
            PromptCompiler.CompiledPrompt prepared, ResponseContract<?> contract) {
        List<ModelMessage> messages = new ArrayList<>(prepared.messages());
        ModelMessage system = messages.getFirst();
        messages.set(
                0,
                new ModelMessage(
                        ModelMessage.Role.SYSTEM,
                        system.content() + "\n本次最终结果必须是符合下列 Schema 的单个 JSON 对象，不使用 Markdown 代码围栏。\n"
                                + contract.schema(),
                        null));
        PromptSnapshot prior = prepared.snapshot();
        var references = new ArrayList<>(prior.contexts());
        references.add(new PromptSnapshot.ContextRef(
                "response-contract", contract.id(), 1, PromptHashes.sha256(contract.schema())));
        var snapshot = new PromptSnapshot(
                prior.purpose(),
                prior.templates(),
                prior.profileId(),
                prior.profileRevision(),
                prior.personaSha256(),
                prior.toolCatalogSha256(),
                references,
                prior.compiledSha256());
        return new PromptCompiler.CompiledPrompt(
                messages, actualSnapshot(snapshot, messages), prepared.conversationStartIndex());
    }

    /** 校验结果，最多一次无工具修复调用且计入相同预算；第二次仍无效则失败，不生成成功产物。 */
    public <T> T validateOrRepair(
            TurnExecutionContext context,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> dialogue,
            ModelResponse response,
            ResponseContract<T> contract,
            ItemSink events)
            throws Exception {
        try {
            if (!response.toolCalls().isEmpty()) {
                throw new IllegalArgumentException("structured result cannot contain tool calls");
            }
            return contract.decode(response.text());
        } catch (IllegalArgumentException invalid) {
            List<ModelMessage> repair = new ArrayList<>(dialogue);
            repair.add(new ModelMessage(ModelMessage.Role.ASSISTANT, response.text(), null));
            repair.add(new ModelMessage(ModelMessage.Role.USER, "上一输出未通过结构化契约校验。只修复 JSON 结构，不调用工具，不扩展任务。", null));
            ModelResponse repaired = stream(
                    context,
                    prepared,
                    repair,
                    List.of(),
                    new ModelStreamSink() {
                        @Override
                        public void text(String fragment) {}

                        @Override
                        public void usage(ModelUsage usage) {
                            events.usage(usage);
                        }
                    },
                    events);
            if (!repaired.toolCalls().isEmpty()) {
                throw new IllegalArgumentException("repair attempted a tool call");
            }
            return contract.decode(repaired.text());
        }
    }

    /** 在显式 TurnScope 下流式调用模型；先记录实际消息摘要再发起请求，失败及取消不会绕过预算。 后续步骤可追加工具结果，但内置模板和来源仍使用 prepared 的原快照。 */
    public ModelResponse stream(
            TurnExecutionContext context,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages,
            List<ToolDescriptor> tools,
            ModelStreamSink output,
            ItemSink events)
            throws Exception {
        return stream(context, prepared, messages, tools, output, events, context.conversationState());
    }

    /** 使用调用方维护的临时 Provider 状态继续同一 tool loop；opaque payload 不进入提示词快照。 */
    public ModelResponse stream(
            TurnExecutionContext context,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages,
            List<ToolDescriptor> tools,
            ModelStreamSink output,
            ItemSink events,
            ProviderConversationState conversationState)
            throws Exception {
        context.throwIfInterrupted();
        var budget = context.scope().budget();
        // 在请求前预留输入保守上界和输出上限，避免并行辅助调用各自看到同一余额。
        // UTF-8 字节数不是费用报告：仅用于拒绝明显超预算请求，最终按 Provider 用量结算。
        long inputBound = inputTokenBound(messages, tools);
        if (budget.remainingTokens() <= inputBound) {
            throw new BudgetExceededException("Turn token budget cannot cover the input context");
        }
        TurnConfig bounded = boundedConfig(context.turn().config(), budget.remainingTokens() - inputBound);
        long reserved = inputBound + Long.parseLong(bounded.attributes().get("maxOutputTokens"));
        try (var invocation = budget.allocate(1, reserved)) {
            int ordinal = invocation.consumeCall(prepared.snapshot().purpose().name());
            events.budget(budget.usedCalls(), budget.usedTokens() + reserved);
            events.promptSnapshot(actualSnapshot(prepared.snapshot(), messages, tools), ordinal);
            ModelRequest request = new ModelRequest(
                    context.thread().id(),
                    context.turn().id(),
                    messages,
                    tools,
                    bounded,
                    conversationState,
                    prepared.conversationStartIndex());
            AtomicBoolean accepting = new AtomicBoolean(true);
            FutureTask<ModelResponse> task = new FutureTask<>(() -> gateway.stream(request, new ModelStreamSink() {
                @Override
                public void text(String fragment) {
                    if (accepting.get()) {
                        output.text(fragment);
                    }
                }

                @Override
                public void reasoningSummary(String fragment) {
                    if (accepting.get()) {
                        output.reasoningSummary(fragment);
                    }
                }

                @Override
                public void usage(ModelUsage usage) {
                    // Provider 可能输出累计流式用量；统一只记最终响应一次，避免重复计费。
                }
            }));
            Thread worker = Thread.ofVirtual().name("javaclaw-model-invocation").start(task);
            boolean charged = false;
            try {
                while (true) {
                    context.throwIfInterrupted();
                    try {
                        ModelResponse response = task.get(
                                Math.min(context.scope().remainingNanos(), TimeUnit.MILLISECONDS.toNanos(100)),
                                TimeUnit.NANOSECONDS);
                        long actual = Math.addExact(
                                response.usage().inputTokens(), response.usage().outputTokens());
                        invocation.chargeTokens(actual > 0 ? actual : inputBound + utf8Size(response.text()));
                        charged = true;
                        output.usage(response.usage());
                        return response;
                    } catch (TimeoutException waiting) {
                        context.throwIfInterrupted();
                    } catch (ExecutionException failure) {
                        if (failure.getCause() instanceof InterruptedException interrupted) {
                            throw interrupted;
                        }
                        if (failure.getCause() instanceof ContextWindowExceededException contextWindow) {
                            throw contextWindow;
                        }
                        if (failure.getCause() instanceof Error error) {
                            throw error;
                        }
                        // Provider 异常可能回显 Authorization、URL 或请求正文；跨入 Item 的错误只保留类型。
                        throw new IllegalStateException("cloud model invocation failed ("
                                + failure.getCause().getClass().getSimpleName()
                                + "); check provider configuration and connectivity");
                    }
                }
            } finally {
                accepting.set(false);
                task.cancel(true);
                worker.interrupt();
                if (!charged) {
                    // 请求已发出但费用未知，保守占用预留额度；不能借超时重新获得无限调用。
                    invocation.chargeTokens(reserved);
                }
                events.budget(budget.usedCalls(), budget.usedTokens());
            }
        }
    }

    /** 无工具辅助调用仍使用同一流式通道、预算与 Item 用量；不暴露临时工具递归。 */
    public ModelResponse complete(TurnExecutionContext context, PromptCompiler.CompiledPrompt prepared, ItemSink events)
            throws Exception {
        return stream(
                context,
                prepared,
                prepared.messages(),
                List.of(),
                new ModelStreamSink() {
                    @Override
                    public void text(String fragment) {}

                    @Override
                    public void usage(ModelUsage usage) {
                        events.usage(usage);
                    }
                },
                events);
    }

    /** 返回当前 Provider 快照的压缩能力；自定义兼容端点不会由 Core 猜测为原生。 */
    public CompactionStrategy compactionStrategy(TurnConfig config) {
        return gateway.compactionStrategy(config);
    }

    /** 在当前 Turn 的取消、调用次数与 token 预算中执行一次原生 compact；失败不会产生替换窗口。 */
    public NativeCompactionResult compact(
            TurnExecutionContext context,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages,
            ProviderConversationState conversationState,
            ItemSink events)
            throws Exception {
        context.throwIfInterrupted();
        long inputBound = inputTokenBound(messages, List.of());
        var budget = context.scope().budget();
        if (budget.remainingTokens() <= inputBound) {
            throw new BudgetExceededException("Turn token budget cannot cover the compaction input");
        }
        TurnConfig bounded = boundedConfig(context.turn().config(), budget.remainingTokens() - inputBound);
        long reserved = inputBound + Long.parseLong(bounded.attributes().get("maxOutputTokens"));
        try (var invocation = budget.allocate(1, reserved)) {
            int ordinal = invocation.consumeCall(prepared.snapshot().purpose().name());
            events.budget(budget.usedCalls(), budget.usedTokens() + reserved);
            events.promptSnapshot(actualSnapshot(prepared.snapshot(), messages, List.of()), ordinal);
            ModelRequest request = new ModelRequest(
                    context.thread().id(),
                    context.turn().id(),
                    messages,
                    List.of(),
                    bounded,
                    conversationState,
                    prepared.conversationStartIndex());
            FutureTask<NativeCompactionResult> task = new FutureTask<>(() -> gateway.compact(request));
            Thread worker =
                    Thread.ofVirtual().name("javaclaw-native-compaction").start(task);
            boolean charged = false;
            try {
                while (true) {
                    context.throwIfInterrupted();
                    try {
                        NativeCompactionResult result = task.get(
                                Math.min(context.scope().remainingNanos(), TimeUnit.MILLISECONDS.toNanos(100)),
                                TimeUnit.NANOSECONDS);
                        long actual = Math.addExact(
                                result.usage().inputTokens(), result.usage().outputTokens());
                        invocation.chargeTokens(actual > 0 ? actual : inputBound);
                        charged = true;
                        events.usage(result.usage());
                        return result;
                    } catch (TimeoutException waiting) {
                        context.throwIfInterrupted();
                    } catch (ExecutionException failure) {
                        if (failure.getCause() instanceof InterruptedException interrupted) {
                            throw interrupted;
                        }
                        if (failure.getCause() instanceof ContextWindowExceededException contextWindow) {
                            throw contextWindow;
                        }
                        if (failure.getCause() instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalStateException("native context compaction failed ("
                                + failure.getCause().getClass().getSimpleName()
                                + "); the previous conversation window remains active");
                    }
                }
            } finally {
                task.cancel(true);
                worker.interrupt();
                if (!charged) {
                    invocation.chargeTokens(reserved);
                }
                events.budget(budget.usedCalls(), budget.usedTokens());
            }
        }
    }

    private static TurnConfig boundedConfig(TurnConfig config, long remainingTokens) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>(config.attributes());
        int requested = Integer.parseInt(attributes.getOrDefault("maxOutputTokens", "4096"));
        if (requested < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        attributes.put("maxOutputTokens", Long.toString(Math.min(requested, remainingTokens)));
        return new TurnConfig(
                config.model(),
                config.provider(),
                config.reasoningEffort(),
                config.workingDirectory(),
                config.sandboxPolicy(),
                config.approvalPolicy(),
                config.enabledTools(),
                attributes);
    }

    private static long inputTokenBound(List<ModelMessage> messages, List<ToolDescriptor> tools) {
        long imageCount =
                messages.stream().mapToLong(message -> message.images().size()).sum();
        long imageBytes = messages.stream()
                .flatMap(message -> message.images().stream())
                .mapToLong(com.javaclaw.core.api.ModelImage::sizeBytes)
                .sum();
        if (imageCount > 20 || imageBytes > 32L * 1024 * 1024) {
            throw new IllegalArgumentException("model image inputs exceed the page or memory limit");
        }
        long size = 256;
        for (ModelMessage message : messages) {
            size = Math.addExact(size, 32L + utf8Size(message.content()));
            // 图片尺寸由输入边界限制为 4096×4096；每张预留保守视觉预算，最终仍按 Provider 用量结算。
            size = Math.addExact(size, 32_768L * message.images().size());
            for (var call : message.toolCalls()) {
                size = Math.addExact(size, utf8Size(call.name()) + utf8Size(call.argumentsJson()) + 32L);
            }
        }
        for (ToolDescriptor tool : tools) {
            size = Math.addExact(
                    size,
                    64L + utf8Size(tool.name()) + utf8Size(tool.description()) + utf8Size(tool.inputSchemaJson()));
        }
        return size;
    }

    private static int utf8Size(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static PromptSnapshot actualSnapshot(PromptSnapshot snapshot, List<ModelMessage> messages) {
        return actualSnapshot(snapshot, messages, null);
    }

    private static PromptSnapshot actualSnapshot(
            PromptSnapshot snapshot, List<ModelMessage> messages, List<ToolDescriptor> tools) {
        List<String> fields = new ArrayList<>();
        var references = new java.util.LinkedHashSet<>(snapshot.contexts());
        for (ModelMessage message : messages) {
            fields.add(message.role().name());
            fields.add(message.content());
            fields.add(Objects.toString(message.toolCallId(), ""));
            fields.add(Objects.toString(message.toolName(), ""));
            message.toolCalls().forEach(call -> fields.addAll(List.of(call.id(), call.name(), call.argumentsJson())));
            message.images().forEach(image -> fields.addAll(List.of(image.sha256(), image.mediaType())));
            message.images()
                    .forEach(image -> references.add(
                            new PromptSnapshot.ContextRef("attachment", image.sha256(), 0, image.sha256())));
            if (message.role() == ModelMessage.Role.TOOL) {
                references.add(new PromptSnapshot.ContextRef(
                        "tool-result", message.toolCallId(), 0, PromptHashes.sha256(message.content())));
            }
        }
        List<String> descriptors = new ArrayList<>();
        if (tools != null) {
            tools.stream()
                    .sorted(java.util.Comparator.comparing(ToolDescriptor::name))
                    .forEach(tool ->
                            descriptors.addAll(List.of(tool.name(), tool.description(), tool.inputSchemaJson())));
        }
        return new PromptSnapshot(
                snapshot.purpose(),
                snapshot.templates(),
                snapshot.profileId(),
                snapshot.profileRevision(),
                snapshot.personaSha256(),
                tools == null ? snapshot.toolCatalogSha256() : PromptHashes.sequence(descriptors),
                List.copyOf(references),
                PromptHashes.sequence(fields));
    }
}
