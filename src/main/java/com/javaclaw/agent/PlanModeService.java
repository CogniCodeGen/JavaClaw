package com.javaclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.memory.MemoryManager;
import com.javaclaw.agent.expert.PlanRole;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.PlanModePrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.pipeline.MsgHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 规划模式服务 — 基于 MsgHub 的多智能体协作讨论（UI 无关）
 *
 * <p>封装 AgentScope MsgHub 协作流程：协调者分析任务、选择专家、多轮讨论、汇总方案。
 * 每位专家的发言通过 {@link ConversationEvent.AgentStart} / {@link ConversationEvent.AgentReply}
 * 广播到调用方。</p>
 *
 * <p>与普通模式的层级委派（SubAgentTool）不同，规划模式采用对等广播：所有参与者都能看到彼此的发言，
 * 适合方案讨论和多角度分析。</p>
 */
public class PlanModeService {

    private static final Logger log = LoggerFactory.getLogger(PlanModeService.class);
    private static final com.javaclaw.workflow.model.GraphDefinition SYSTEM_GRAPH =
            com.javaclaw.workflow.service.SystemGraphFactory.plan();

    /** 协调者结束讨论的标记 */
    private static final String PLAN_COMPLETE_MARKER = "[PLAN_COMPLETE]";

    /** JSON 解析器（用于解析协调者首轮输出的结构化专家选择） */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 规划协调者 */
    private final ReActAgent coordinator;

    /** 流式输出选项 */
    private final StreamOptions streamOptions;

    /** 所有可用的规划模式专家（名称 → 智能体） */
    private final Map<String, ReActAgent> expertAgents = new LinkedHashMap<>();
    private final Map<String, PlanRole> planRoles;
    private final ChatModelBase profileClassifier;
    private final GenerateOptions profileGenerateOptions = GenerateOptions.builder().build();

    /** 共享基础设施（模型工厂 / 记忆 / 知识 / token 追踪等） */
    private final AgentRuntime runtime;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private final com.javaclaw.api.conversation.SingleConversationRun conversationRun =
            new com.javaclaw.api.conversation.SingleConversationRun();

    /** 统一记忆管理器（从 runtime 取出的快捷引用） */
    private final MemoryManager memoryManager;

    /** 取消标志 */
    private volatile boolean cancelled = false;

    /** 当前活跃的订阅 */
    private volatile Disposable activeSubscription;

    public PlanModeService(AgentRuntime runtime) {
        this(runtime, null);
    }

    public PlanModeService(AgentRuntime runtime, com.javaclaw.workflow.service.WorkflowService workflowService) {
        this.runtime = runtime;
        this.workflowService = workflowService;
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
        this.memoryManager = runtime.getMemoryManager();
        AgentConfig config = AgentConfig.getInstance();
        log.info("========== 初始化规划模式服务 ==========");

        // 先创建专家，再用真实专家名清单动态拼接协调者系统提示词，
        // 避免协调者只看到静态常量里写死的子集（参见 issue：内置评估/命令行专家与自定义专家被漏选）。
        expertAgents.putAll(runtime.getExpertManager()
                .createPlanModeAgents(runtime.getModelFactory()));
        this.planRoles = runtime.getExpertManager().getPlanRoles();
        this.profileClassifier = runtime.getModelFactory().createLightChatModel();

        String coordinatorSysPrompt = PlanModePrompts.coordinatorSysPrompt(domainExpertNames());

        this.coordinator = ReActAgent.builder()
                .name(AgentConfig.PLAN_COORDINATOR_NAME)
                .sysPrompt(coordinatorSysPrompt)
                .model(runtime.getModelFactory().createHighMultiAgentChatModel())
                .maxIters(1)
                .build();
        log.info("规划协调者已创建: {}，可选专家 {} 个",
                AgentConfig.PLAN_COORDINATOR_NAME, expertAgents.size());

        // 流式输出选项：三条编排路径共用 ToolkitAssembler.buildStreamOptions 单一来源
        this.streamOptions = ToolkitAssembler.buildStreamOptions(config);

        log.info("========== 规划模式服务初始化完成 ==========");
    }

    // ==================== 公开入口：流式对话 ====================

    /**
     * 规划模式对外入口：接收用户请求，驱动多智能体协作讨论，事件流式回调结果。
     *
     * @param request   用户请求（文本 + 附件）
     * @param callbacks 事件与生命周期回调
     */
    public com.javaclaw.api.conversation.ConversationHandle planChat(
            ConversationRequest request, ConversationCallbacks callbacks) {
        return conversationRun.start(callbacks,
                guarded -> startPlanPipeline(request, guarded),
                ignored -> cancel(request.sessionId()));
    }

    private void startPlanPipeline(ConversationRequest request, ConversationCallbacks callbacks) {
        if (workflowService == null) {
            executePlanPipeline(request, callbacks);
            return;
        }
        workflowService.runSystem(SYSTEM_GRAPH, request.sessionId(),
                com.javaclaw.workflow.service.SystemInvocationState.from(request), callbacks,
                this::executePlanGraphStage);
    }

    private void executePlanPipeline(ConversationRequest request, ConversationCallbacks callbacks) {
        Mono.fromCallable(() -> runtime.enrichWithKnowledge(request.userInput()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(enrichedInput -> executePlanWithEnrichedInput(request, enrichedInput, callbacks),
                        error -> {
                            log.error("知识库检索异常，降级为原始输入", error);
                            executePlanWithEnrichedInput(request, request.userInput(), callbacks);
                        });
    }

    private com.javaclaw.workflow.runtime.NodeResult executePlanGraphStage(
            String stageId, com.javaclaw.workflow.runtime.NodeExecutionContext context) throws Exception {
        ConversationRequest request = com.javaclaw.workflow.service.SystemInvocationState.request(context);
        return switch (stageId) {
            case "knowledge" -> {
                String enriched;
                try {
                    enriched = runtime.enrichWithKnowledge(request.userInput());
                } catch (Exception failure) {
                    log.warn("规划图知识增强失败，降级为原始输入: {}", failure.getMessage());
                    enriched = request.userInput();
                }
                yield com.javaclaw.workflow.runtime.NodeResult.next(
                        com.javaclaw.workflow.model.StatePatch.builder()
                                .set("system.plan.enrichedInput", enriched).build());
            }
            case "discussion" -> {
                String enriched = context.state().get("system.plan.enrichedInput")
                        .asText(request.userInput());
                yield com.javaclaw.workflow.service.SystemPipelineAwaiter.await(
                        context,
                        inner -> executePlanWithEnrichedInput(request, enriched, inner),
                        context.require(ConversationCallbacks.class),
                        this::cancelPipeline);
            }
            default -> throw new IllegalArgumentException("未知研讨系统阶段: " + stageId);
        };
    }

    private void executePlanWithEnrichedInput(
            ConversationRequest request, String enrichedInput, ConversationCallbacks callbacks) {
        String userInput = request.userInput();
        List<File> attachments = request.attachments();

        log.info("收到用户消息（规划模式）: {}", userInput);

        final int inputCharCount = userInput.length();
        final AtomicInteger outputCharCount = new AtomicInteger(0);
        runtime.getTokenTracker().beginStreaming(inputCharCount);

        // 领域层包装器：拦截 Usage / AgentReply 做 token 簿记，然后转发给 UI
        final ConversationCallbacks domainCallbacks = new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                try {
                    if (event instanceof ConversationEvent.Usage u) {
                        runtime.getTokenTracker().addStreamingUsage(u.inputTokens(), u.outputTokens());
                    } else if (event instanceof ConversationEvent.AgentReply ar) {
                        outputCharCount.addAndGet(ar.chunk().length());
                        runtime.getTokenTracker().addStreamingChars(ar.chunk().length());
                    }
                } catch (Throwable t) {
                    log.error("规划模式领域簿记异常，继续转发事件", t);
                }
                callbacks.onEvent(event);
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                callbacks.onTerminal(outcome);
            }
        };

        Msg userMsg = runtime.buildUserMsg(enrichedInput, attachments);
        runPlanDiscussion(userMsg, request.options().planProfile(), domainCallbacks);
    }

    /**
     * 启动规划模式多智能体协作讨论（已拿到用户 Msg）。
     */
    private void runPlanDiscussion(Msg userMsg, PlanProfile requestedProfile,
                                   ConversationCallbacks callbacks) {
        cancelled = false;

        activeSubscription = Mono.fromRunnable(() -> {
                    try {
                        ProfileResolution resolution =
                                resolveProfile(requestedProfile, userMsg.getTextContent(), callbacks);
                        PlanLimits limits = PlanLimits.forProfile(resolution.profile());
                        PlanBudget budget = new PlanBudget(limits, resolution.classifierTokens());
                        callbacks.onEvent(new ConversationEvent.Hint(
                                "[规划·档位] " + resolution.profile()
                                        + " · 最多 " + limits.maxExperts() + " 位专家 / "
                                        + limits.rounds() + " 轮 / "
                                        + limits.tokenBudget() + " tokens"));
                        executePlanDiscussion(userMsg, limits, budget, callbacks);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> {},
                        error -> {
                            activeSubscription = null;
                            Throwable cause = error instanceof RuntimeException && error.getCause() != null
                                    ? error.getCause() : error;
                            log.error("规划模式讨论出错", cause);
                            callbacks.onTerminal(ConversationOutcome.failed(cause));
                        },
                        () -> {
                            activeSubscription = null;
                            log.info("规划模式讨论完成");
                            callbacks.onTerminal(ConversationOutcome.completed());
                        }
                );
    }

    /**
     * 执行规划讨论的核心流程（在 boundedElastic 线程中运行，流式输出）。
     */
    private void executePlanDiscussion(Msg userMsg,
                                       PlanLimits limits,
                                       PlanBudget budget,
                                       ConversationCallbacks callbacks) {
        String coordinatorName = AgentConfig.PLAN_COORDINATOR_NAME;

        // 1. 协调者分析任务并选择专家（流式输出）
        callbacks.onEvent(new ConversationEvent.Hint(
                "[规划·" + coordinatorName + "] 正在分析任务并挑选专家..."));
        observe(coordinator, userMsg, budget, false);
        if (cancelled) return;

        String firstResponse = streamAgentResponse(coordinator, callbacks, budget, false);
        log.info("协调者首轮发言: {} 字符", firstResponse.length());

        // 2. 解析参与专家列表（含重试机制）
        List<ReActAgent> selectedExperts = selectExperts(firstResponse, limits.maxExperts());
        if (selectedExperts.isEmpty() && !cancelled && budget.canStartDiscussionCall()) {
            log.warn("首轮专家选择 JSON 解析失败，发送修正提示进行重试...");
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·" + coordinatorName + "] 专家选择 JSON 解析失败，正在重试..."));
            String retryPrompt = PlanModePrompts.expertSelectionRetry(domainExpertNames());
            observe(coordinator, Msg.builder().role(MsgRole.USER).name("system")
                    .textContent(retryPrompt).build(), budget, false);
            String retryResponse = streamAgentResponse(coordinator, callbacks, budget, false);
            selectedExperts = selectExperts(retryResponse, limits.maxExperts());
        }
        if (selectedExperts.isEmpty()) {
            log.warn("重试后仍未能解析参与专家，使用默认专家（编程 + 知识）");
            selectedExperts = getDefaultExperts();
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·" + coordinatorName + "] 未能选择专家，已使用默认专家组合"));
        }
        selectedExperts = ensureMinimumExperts(
                selectedExperts, limits.minExperts(), limits.maxExperts());
        List<String> selectedNames = selectedExperts.stream()
                .map(AgentBase::getName).toList();
        log.info("选中专家: {}", selectedNames);
        callbacks.onEvent(new ConversationEvent.Hint(
                "[规划·" + coordinatorName + "] 已选定 " + selectedNames.size()
                        + " 位专家：" + String.join("、", selectedNames)));

        // 3. 构建 MsgHub 参与者列表（协调者 + 选中专家）
        List<AgentBase> participants = new ArrayList<>();
        participants.add(coordinator);
        participants.addAll(selectedExperts);

        // 4. 构建公告消息：只携带用户原始需求 + 协调者选定的 topic。
        //    若塞入 firstResponse 全文，专家会倾向于复述协调者的方案草案，各专家输出趋同。
        StringBuilder announcementText = new StringBuilder(userMsg.getTextContent());
        String coordinatorJson = extractJsonBlock(firstResponse);
        if (coordinatorJson != null) {
            try {
                String topic = objectMapper.readTree(coordinatorJson).path("topic").asText("");
                if (!topic.isBlank()) {
                    announcementText.append(PlanModePrompts.announcementTopic(topic));
                }
            } catch (Exception e) {
                log.debug("解析协调者 topic 失败，announcement 退化为纯用户消息", e);
            }
        }
        announcementText.append(PlanModePrompts.ANNOUNCEMENT_PERSPECTIVE_SUFFIX);
        Msg announcement = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .textContent(announcementText.toString())
                .build();

        // 5. 在 MsgHub 中进行多轮讨论（流式输出每位智能体的发言）
        try (MsgHub hub = MsgHub.builder()
                .name("plan_discussion")
                .participants(participants.toArray(new AgentBase[0]))
                .announcement(announcement)
                .enableAutoBroadcast(true)
                .build()) {

            hub.enter().block();
            log.info("MsgHub 已建立，参与者: {} 个", participants.size());
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·MsgHub] 协作通道建立，参与者 " + participants.size()
                            + " 位（含协调者）"));

            String finalDraft = "";
            boolean discussionStoppedForBudget = false;
            for (int round = 1; round <= limits.rounds(); round++) {
                if (cancelled) break;
                if (!budget.canStartDiscussionCall()) {
                    discussionStoppedForBudget = true;
                    callbacks.onEvent(new ConversationEvent.Hint(
                            "[规划·预算] 已到 80% 预留线，停止新讨论并进入最终汇总"));
                    break;
                }

                String roundTag = "[规划·第 " + round + "/" + limits.rounds() + " 轮]";
                callbacks.onEvent(new ConversationEvent.Hint(
                        roundTag + " 讨论开始（" + selectedExperts.size() + " 位专家依次发言）"));
                log.info("===== 第 {} 轮讨论 =====", round);

                int idx = 0;
                for (ReActAgent expert : selectedExperts) {
                    if (cancelled) break;
                    if (!budget.canStartDiscussionCall()) {
                        discussionStoppedForBudget = true;
                        break;
                    }
                    idx++;
                    callbacks.onEvent(new ConversationEvent.Hint(
                            roundTag + " (" + idx + "/" + selectedExperts.size() + ") "
                                    + expert.getName() + " 正在发言..."));
                    String expertText = streamAgentResponse(expert, callbacks, budget, false);
                    log.info("[{}] 发言: {} 字符", expert.getName(), expertText.length());
                }

                if (cancelled) break;
                if (discussionStoppedForBudget) break;

                callbacks.onEvent(new ConversationEvent.Hint(
                        roundTag + " " + coordinatorName + " 正在汇总本轮发言..."));
                String coordText = streamAgentResponse(coordinator, callbacks, budget, false);
                finalDraft = coordText;
                log.info("[协调者] 汇总: {} 字符", coordText.length());

                if (coordText.contains(PLAN_COMPLETE_MARKER)) {
                    log.info("协调者宣布讨论完成（第 {} 轮）", round);
                    callbacks.onEvent(new ConversationEvent.Hint(
                            roundTag + " " + coordinatorName + " 宣布讨论达成共识"));
                    break;
                }

                callbacks.onEvent(new ConversationEvent.Hint(roundTag + " 本轮讨论完成"));
            }

            if (!cancelled && !finalDraft.contains(PLAN_COMPLETE_MARKER)
                    && budget.canStartFinalCall()) {
                callbacks.onEvent(new ConversationEvent.Hint(
                        "[规划·最终汇总] 正在使用预留预算生成完整方案..."));
                Msg finalRequest = Msg.builder().role(MsgRole.USER).name("system")
                        .textContent("请基于全部讨论输出最终可执行方案。必须以 "
                                + PLAN_COMPLETE_MARKER + " 结束，不再发起新讨论。")
                        .build();
                observe(coordinator, finalRequest, budget, true);
                finalDraft = streamAgentResponse(coordinator, callbacks, budget, true);
            }

            String completedDraft = finalDraft;
            boolean shouldRunCritic = limits.profile() != PlanProfile.QUICK
                    && budget.canStartFinalCall();
            publishFinalDraftAndReview(completedDraft, callbacks, shouldRunCritic,
                    () -> cancelled, () -> {
                        ReActAgent critic = criticAgent();
                        if (critic == null) return;
                        callbacks.onEvent(new ConversationEvent.Hint(
                                "[规划·评审] 任务评估专家正在审阅最终方案..."));
                        observe(critic, Msg.builder().role(MsgRole.USER).name("system")
                                .textContent("仅评审以下最终方案，指出关键风险、遗漏和可执行修正；"
                                        + "不要参与前序讨论：\n\n" + completedDraft)
                                .build(), budget, true);
                        streamAgentResponse(critic, callbacks, budget, true);
                    });
        } catch (Exception e) {
            log.error("MsgHub 讨论过程中出错", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 先锁定协调者最终方案，再把 Critic 作为可降级的附加评审运行。
     *
     * <p>包级可见以便验证事件顺序；Critic 失败不得把已经完成的方案改成失败终态。</p>
     */
    static void publishFinalDraftAndReview(String finalDraft,
                                           ConversationCallbacks callbacks,
                                           boolean shouldRunCritic,
                                           BooleanSupplier cancellation,
                                           Runnable criticAction) {
        if (finalDraft == null || finalDraft.isBlank() || cancellation.getAsBoolean()) return;
        callbacks.onEvent(new ConversationEvent.Custom(
                "plan_final", finalDraft.replace(PLAN_COMPLETE_MARKER, "").strip()));
        if (!shouldRunCritic || cancellation.getAsBoolean()) return;
        try {
            criticAction.run();
        } catch (RuntimeException reviewFailure) {
            if (cancellation.getAsBoolean()) return;
            log.warn("最终方案 Critic 评审失败，保留协调者方案: {}",
                    reviewFailure.getMessage());
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·评审] 评审未完成，已保留协调者最终方案"));
        }
    }

    private ProfileResolution resolveProfile(PlanProfile requested, String input,
                                             ConversationCallbacks callbacks) {
        if (requested != null && requested != PlanProfile.AUTO) {
            return new ProfileResolution(requested, 0);
        }
        try {
            if (cancelled) throw new java.util.concurrent.CancellationException("研讨已取消");
            Msg system = Msg.builder().role(MsgRole.SYSTEM).name("system")
                    .textContent("""
                            只判断研讨深度，严格输出一个枚举：QUICK、STANDARD 或 DEEP。
                            QUICK=简单决策/单领域；STANDARD=跨领域且有明确取舍；
                            DEEP=高风险、复杂系统设计或需要多轮反驳。
                            """).build();
            Msg user = Msg.builder().role(MsgRole.USER).name("user")
                    .textContent(input == null ? "" : input).build();
            List<ChatResponse> responses = profileClassifier.stream(
                    List.of(system, user), List.of(), profileGenerateOptions)
                    .collectList().block(Duration.ofSeconds(8));
            if (cancelled) throw new java.util.concurrent.CancellationException("研讨已取消");
            long[] usage = TokenTracker.extractUsage(responses);
            callbacks.onEvent(new ConversationEvent.Usage(usage[0], usage[1]));
            StringBuilder text = new StringBuilder();
            if (responses != null) {
                for (ChatResponse response : responses) {
                    if (response.getContent() == null) continue;
                    for (var block : response.getContent()) {
                        if (block instanceof TextBlock value && value.getText() != null) {
                            text.append(value.getText());
                        }
                    }
                }
            }
            PlanProfile resolved = parsePlanProfile(text.toString()).orElse(PlanProfile.QUICK);
            return new ProfileResolution(resolved, usage[0] + usage[1]);
        } catch (Exception e) {
            log.warn("研讨档位自动分类失败，回退 QUICK: {}", e.getMessage());
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·档位] 自动分类超时或失败，已回退 QUICK"));
            return new ProfileResolution(PlanProfile.QUICK, 0);
        }
    }

    /**
     * 从分类器输出中只接受唯一一种规划档位。多个不同枚举同时出现说明模型没有遵守
     * 单值输出契约，宁可回退也不按子串顺序猜测。
     */
    static java.util.Optional<PlanProfile> parsePlanProfile(String output) {
        if (output == null || output.isBlank()) return java.util.Optional.empty();
        java.util.EnumSet<PlanProfile> matches = java.util.EnumSet.noneOf(PlanProfile.class);
        var matcher = java.util.regex.Pattern.compile(
                "\\b(QUICK|STANDARD|DEEP)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(output);
        while (matcher.find()) {
            matches.add(PlanProfile.valueOf(
                    matcher.group(1).toUpperCase(java.util.Locale.ROOT)));
        }
        return matches.size() == 1
                ? java.util.Optional.of(matches.iterator().next())
                : java.util.Optional.empty();
    }

    private void observe(ReActAgent agent, Msg message, PlanBudget budget, boolean finalPhase) {
        budget.requireCallAllowed(finalPhase);
        agent.observe(message).block(Duration.ofMillis(budget.remainingMillis()));
        budget.requireWithinHardLimits();
    }

    private java.util.Set<String> domainExpertNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String name : expertAgents.keySet()) {
            if (planRoles.getOrDefault(name, PlanRole.DOMAIN) == PlanRole.DOMAIN) names.add(name);
        }
        return names;
    }

    private ReActAgent criticAgent() {
        for (Map.Entry<String, ReActAgent> entry : expertAgents.entrySet()) {
            if (planRoles.get(entry.getKey()) == PlanRole.CRITIC) return entry.getValue();
        }
        return null;
    }

    private List<ReActAgent> ensureMinimumExperts(List<ReActAgent> selected,
                                                   int minimum, int maximum) {
        List<ReActAgent> result = new ArrayList<>(selected);
        for (String name : domainExpertNames()) {
            if (result.size() >= minimum || result.size() >= maximum) break;
            ReActAgent candidate = expertAgents.get(name);
            if (candidate != null && !result.contains(candidate)) result.add(candidate);
        }
        return result;
    }

    private record ProfileResolution(PlanProfile profile, long classifierTokens) {}

    private record PlanLimits(
            PlanProfile profile,
            int minExperts,
            int maxExperts,
            int rounds,
            int timeoutSeconds,
            long tokenBudget
    ) {
        static PlanLimits forProfile(PlanProfile profile) {
            return switch (profile) {
                case QUICK, AUTO -> new PlanLimits(PlanProfile.QUICK, 1, 2, 1, 60, 20_000);
                case STANDARD -> new PlanLimits(PlanProfile.STANDARD, 2, 3, 2, 120, 50_000);
                case DEEP -> new PlanLimits(PlanProfile.DEEP, 3, 4, 3, 180, 100_000);
            };
        }
    }

    private final class PlanBudget {
        private final PlanLimits limits;
        private final long startedNanos = System.nanoTime();
        private final AtomicLong tokens = new AtomicLong();

        private PlanBudget(PlanLimits limits, long initialTokens) {
            this.limits = limits;
            this.tokens.set(Math.max(0, initialTokens));
        }

        void addUsage(long input, long output) {
            tokens.addAndGet(Math.max(0, input) + Math.max(0, output));
        }

        boolean canStartDiscussionCall() {
            return !cancelled
                    && tokens.get() < Math.round(limits.tokenBudget() * 0.8)
                    && elapsedMillis() < limits.timeoutSeconds() * 800L;
        }

        boolean canStartFinalCall() {
            return !cancelled
                    && tokens.get() < limits.tokenBudget()
                    && elapsedMillis() < limits.timeoutSeconds() * 1000L;
        }

        void requireCallAllowed(boolean finalPhase) {
            if (cancelled) throw new java.util.concurrent.CancellationException("研讨已取消");
            if (finalPhase ? !canStartFinalCall() : !canStartDiscussionCall()) {
                throw new IllegalStateException(finalPhase
                        ? "研讨最终汇总预算或时间已耗尽"
                        : "研讨已到 80% 预留线");
            }
        }

        void requireWithinHardLimits() {
            if (cancelled) throw new java.util.concurrent.CancellationException("研讨已取消");
            if (elapsedMillis() >= limits.timeoutSeconds() * 1000L) {
                throw new IllegalStateException("研讨超过 " + limits.timeoutSeconds() + " 秒时间预算");
            }
            if (tokens.get() >= limits.tokenBudget()) {
                throw new IllegalStateException("研讨超过 " + limits.tokenBudget() + " token 预算");
            }
        }

        long remainingMillis() {
            return Math.max(1, limits.timeoutSeconds() * 1000L - elapsedMillis());
        }

        private long elapsedMillis() {
            return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }

    /**
     * 流式调用智能体并收集完整响应文本，同时把事件流转译为 {@link ConversationEvent}。
     *
     * @return 智能体的完整回复文本
     */
    private String streamAgentResponse(ReActAgent agent, ConversationCallbacks callbacks,
                                       PlanBudget budget, boolean finalPhase) {
        budget.requireCallAllowed(finalPhase);
        String agentName = agent.getName();
        callbacks.onEvent(new ConversationEvent.AgentStart(agentName));
        StringBuilder fullText = new StringBuilder();

        try {
            agent.stream(streamOptions)
                    .doOnNext(event -> {
                        if (cancelled) return;
                        Msg msg = event.getMessage();
                        if (msg == null) return;

                        // 从 Msg 读取 API 返回的真实 token 用量
                        try {
                            ChatUsage usage = msg.getChatUsage();
                            if (usage != null
                                    && (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0)) {
                                callbacks.onEvent(new ConversationEvent.Usage(
                                        usage.getInputTokens(), usage.getOutputTokens()));
                                budget.addUsage(usage.getInputTokens(), usage.getOutputTokens());
                            }
                        } catch (Throwable t) {
                            log.debug("读取规划模式 ChatUsage 失败，忽略", t);
                        }

                        switch (event.getType()) {
                            case AGENT_RESULT -> {
                                String text = msg.getTextContent();
                                if (text != null && !text.isEmpty()) {
                                    fullText.append(text);
                                    callbacks.onEvent(new ConversationEvent.AgentReply(agentName, text));
                                }
                            }
                            case REASONING -> {
                                List<ThinkingBlock> blocks = msg.getContentBlocks(ThinkingBlock.class);
                                if (blocks != null && !blocks.isEmpty()) {
                                    for (ThinkingBlock block : blocks) {
                                        String thinking = block.getThinking();
                                        if (thinking != null && !thinking.isEmpty()) {
                                            callbacks.onEvent(new ConversationEvent.Thinking(thinking));
                                        }
                                    }
                                } else {
                                    String text = msg.getTextContent();
                                    if (text != null && !text.isEmpty()) {
                                        callbacks.onEvent(new ConversationEvent.Thinking(text));
                                    }
                                }
                            }
                            case TOOL_RESULT -> {
                                // 工具调用结果用 AgentReply 连带展示，确保 UI 可见
                                List<ToolResultBlock> resultBlocks =
                                        msg.getContentBlocks(ToolResultBlock.class);
                                if (resultBlocks != null && !resultBlocks.isEmpty()) {
                                    for (ToolResultBlock block : resultBlocks) {
                                        StringBuilder sb = new StringBuilder();
                                        if (block.getOutput() != null) {
                                            for (var contentBlock : block.getOutput()) {
                                                if (contentBlock instanceof TextBlock textBlock) {
                                                    sb.append(textBlock.getText());
                                                }
                                            }
                                        }
                                        String content = sb.toString();
                                        if (!content.isEmpty()) {
                                            String toolName = block.getName() != null ? block.getName() : "tool";
                                            String chunk = "\n[" + toolName + "] " + content + "\n";
                                            callbacks.onEvent(new ConversationEvent.AgentReply(agentName, chunk));
                                        }
                                    }
                                }
                            }
                            default -> { /* HINT 等其他事件类型 */ }
                        }
                    })
                    .blockLast(Duration.ofMillis(budget.remainingMillis()));
            budget.requireWithinHardLimits();
        } catch (Exception e) {
            if (!cancelled) {
                log.error("规划模式智能体 [{}] 流式调用异常", agentName, e);
                throw e instanceof RuntimeException runtimeFailure
                        ? runtimeFailure : new RuntimeException(e);
            }
        }

        return fullText.toString();
    }

    /**
     * 从协调者首轮回复中解析参与专家列表（JSON 块）。
     */
    private List<ReActAgent> selectExperts(String coordinatorResponse, int maxExperts) {
        try {
            String json = extractJsonBlock(coordinatorResponse);
            if (json == null) {
                log.warn("协调者回复中未找到 JSON 块");
                return List.of();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode expertsNode = root.path("experts");
            if (!expertsNode.isArray()) {
                log.warn("JSON 中缺少 experts 数组");
                return List.of();
            }

            List<ReActAgent> selected = new ArrayList<>();
            for (JsonNode nameNode : expertsNode) {
                String name = nameNode.asText("").trim();
                ReActAgent agent = expertAgents.get(name);
                PlanRole role = planRoles.getOrDefault(name, PlanRole.DOMAIN);
                if (agent != null && role == PlanRole.DOMAIN && selected.size() < maxExperts) {
                    selected.add(agent);
                } else if (agent != null && role != PlanRole.DOMAIN) {
                    log.info("专家 [{}] 角色为 {}，不进入讨论轮", name, role);
                } else if (agent == null && !name.isEmpty()) {
                    log.warn("未知专家名称: {}", name);
                }
            }
            return selected;
        } catch (Exception e) {
            log.warn("解析协调者专家选择 JSON 失败", e);
            return List.of();
        }
    }

    /** 从文本中提取 ```json ... ``` 代码块内容；未找到返回 null */
    private String extractJsonBlock(String text) {
        int start = text.indexOf("```json");
        if (start < 0) return null;
        start = text.indexOf('\n', start);
        if (start < 0) return null;
        int end = text.indexOf("```", start + 1);
        if (end < 0) return null;
        return text.substring(start + 1, end).trim();
    }

    /** 默认专家列表（编程专家 + 知识专家） */
    private List<ReActAgent> getDefaultExperts() {
        List<ReActAgent> defaults = new ArrayList<>();
        ReActAgent coding = expertAgents.get(AgentConfig.CODING_AGENT_NAME);
        ReActAgent knowledge = expertAgents.get(AgentConfig.KNOWLEDGE_AGENT_NAME);
        if (coding != null) defaults.add(coding);
        if (knowledge != null) defaults.add(knowledge);
        return defaults;
    }

    /**
     * 取消当前规划讨论。
     *
     * @return true 表示成功取消
     */
    public boolean cancel() {
        return conversationRun.cancelActive(
                com.javaclaw.api.conversation.CancellationReason.USER_REQUEST);
    }

    /** 只取消指定会话对应的系统图运行；由本轮句柄捕获调用。 */
    public boolean cancel(String sessionId) {
        boolean graphCancelled = workflowService != null
                && workflowService.cancelSystem(SYSTEM_GRAPH.id(), sessionId);
        return cancelPipeline() || graphCancelled;
    }

    private boolean cancelPipeline() {
        cancelled = true;
        Disposable sub = activeSubscription;
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
            activeSubscription = null;
            log.info("规划模式讨论已取消");
            return true;
        }
        return false;
    }

    /** 清空所有智能体的对话记忆 */
    public void clearHistory() {
        memoryManager.clearAgentMemory(coordinator);
        for (ReActAgent agent : expertAgents.values()) {
            memoryManager.clearAgentMemory(agent);
        }
        log.info("规划模式对话历史已清空");
    }

    /** 获取协调者智能体（用于会话持久化） */
    public ReActAgent getCoordinator() {
        return coordinator;
    }

    public void shutdown() {
        conversationRun.cancelActive(
                com.javaclaw.api.conversation.CancellationReason.SHUTDOWN);
        log.info("规划模式服务已关闭");
    }
}
