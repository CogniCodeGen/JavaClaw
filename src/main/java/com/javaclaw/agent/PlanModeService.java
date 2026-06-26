package com.javaclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.memory.MemoryManager;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.PlanModePrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.pipeline.MsgHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 共享基础设施（模型工厂 / 记忆 / 知识 / token 追踪等） */
    private final AgentRuntime runtime;

    /** 统一记忆管理器（从 runtime 取出的快捷引用） */
    private final MemoryManager memoryManager;

    /** 取消标志 */
    private volatile boolean cancelled = false;

    /** 当前活跃的订阅 */
    private volatile Disposable activeSubscription;

    public PlanModeService(AgentRuntime runtime) {
        this.runtime = runtime;
        this.memoryManager = runtime.getMemoryManager();
        AgentConfig config = AgentConfig.getInstance();
        log.info("========== 初始化规划模式服务 ==========");

        // 先创建专家，再用真实专家名清单动态拼接协调者系统提示词，
        // 避免协调者只看到静态常量里写死的子集（参见 issue：内置评估/命令行专家与自定义专家被漏选）。
        expertAgents.putAll(runtime.getExpertManager()
                .createPlanModeAgents(runtime.getModelFactory()));

        String coordinatorSysPrompt = PlanModePrompts.coordinatorSysPrompt(expertAgents.keySet());

        this.coordinator = ReActAgent.builder()
                .name(AgentConfig.PLAN_COORDINATOR_NAME)
                .sysPrompt(coordinatorSysPrompt)
                .model(runtime.getModelFactory().createHighMultiAgentChatModel())
                .maxIters(1)
                .build();
        log.info("规划协调者已创建: {}，可选专家 {} 个",
                AgentConfig.PLAN_COORDINATOR_NAME, expertAgents.size());

        StreamOptions.Builder streamBuilder = StreamOptions.builder().incremental(true);
        if (config.isThinkingEnabled()) {
            streamBuilder.includeReasoningChunk(true)
                    .includeReasoningResult(false)
                    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT,
                            EventType.HINT, EventType.AGENT_RESULT);
        } else {
            streamBuilder.eventTypes(EventType.TOOL_RESULT,
                    EventType.HINT, EventType.AGENT_RESULT);
        }
        this.streamOptions = streamBuilder.build();

        log.info("========== 规划模式服务初始化完成 ==========");
    }

    // ==================== 公开入口：流式对话 ====================

    /**
     * 规划模式对外入口：接收用户请求，驱动多智能体协作讨论，事件流式回调结果。
     *
     * @param request   用户请求（文本 + 附件）
     * @param callbacks 事件与生命周期回调
     */
    public void planChat(ConversationRequest request, ConversationCallbacks callbacks) {
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
            public void onComplete() {
                runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                callbacks.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                callbacks.onError(error);
            }
        };

        // 知识库增强在后台线程执行，避免阻塞调用方
        Mono.fromCallable(() -> runtime.enrichWithKnowledge(userInput))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(enrichedInput -> {
                    Msg userMsg = runtime.buildUserMsg(enrichedInput, attachments);
                    runPlanDiscussion(userMsg, domainCallbacks);
                }, error -> {
                    log.error("知识库检索异常，降级为原始输入", error);
                    Msg userMsg = runtime.buildUserMsg(userInput, attachments);
                    runPlanDiscussion(userMsg, domainCallbacks);
                });
    }

    /**
     * 启动规划模式多智能体协作讨论（已拿到用户 Msg）。
     */
    private void runPlanDiscussion(Msg userMsg, ConversationCallbacks callbacks) {
        cancelled = false;
        AgentConfig config = AgentConfig.getInstance();
        int maxRounds = config.getPlanModeMaxRounds();
        int maxExperts = config.getPlanModeMaxExperts();

        log.info("规划模式启动 — maxRounds: {}, maxExperts: {}", maxRounds, maxExperts);

        activeSubscription = Mono.fromRunnable(() -> {
                    try {
                        executePlanDiscussion(userMsg, maxRounds, maxExperts, callbacks);
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
                            callbacks.onError(cause);
                        },
                        () -> {
                            activeSubscription = null;
                            log.info("规划模式讨论完成");
                            callbacks.onComplete();
                        }
                );
    }

    /**
     * 执行规划讨论的核心流程（在 boundedElastic 线程中运行，流式输出）。
     */
    private void executePlanDiscussion(Msg userMsg,
                                       int maxRounds,
                                       int maxExperts,
                                       ConversationCallbacks callbacks) {
        String coordinatorName = AgentConfig.PLAN_COORDINATOR_NAME;

        // 1. 协调者分析任务并选择专家（流式输出）
        callbacks.onEvent(new ConversationEvent.Hint(
                "[规划·" + coordinatorName + "] 正在分析任务并挑选专家..."));
        coordinator.observe(userMsg).block();
        if (cancelled) return;

        String firstResponse = streamAgentResponse(coordinator, callbacks);
        log.info("协调者首轮发言: {} 字符", firstResponse.length());

        // 2. 解析参与专家列表（含重试机制）
        List<ReActAgent> selectedExperts = selectExperts(firstResponse, maxExperts);
        if (selectedExperts.isEmpty() && !cancelled) {
            log.warn("首轮专家选择 JSON 解析失败，发送修正提示进行重试...");
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·" + coordinatorName + "] 专家选择 JSON 解析失败，正在重试..."));
            String retryPrompt = PlanModePrompts.expertSelectionRetry(expertAgents.keySet());
            coordinator.observe(Msg.builder().role(MsgRole.USER).name("system")
                    .textContent(retryPrompt).build()).block();
            String retryResponse = streamAgentResponse(coordinator, callbacks);
            selectedExperts = selectExperts(retryResponse, maxExperts);
        }
        if (selectedExperts.isEmpty()) {
            log.warn("重试后仍未能解析参与专家，使用默认专家（编程 + 知识）");
            selectedExperts = getDefaultExperts();
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[规划·" + coordinatorName + "] 未能选择专家，已使用默认专家组合"));
        }
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

            for (int round = 1; round <= maxRounds; round++) {
                if (cancelled) break;

                String roundTag = "[规划·第 " + round + "/" + maxRounds + " 轮]";
                callbacks.onEvent(new ConversationEvent.Hint(
                        roundTag + " 讨论开始（" + selectedExperts.size() + " 位专家依次发言）"));
                log.info("===== 第 {} 轮讨论 =====", round);

                int idx = 0;
                for (ReActAgent expert : selectedExperts) {
                    if (cancelled) break;
                    idx++;
                    callbacks.onEvent(new ConversationEvent.Hint(
                            roundTag + " (" + idx + "/" + selectedExperts.size() + ") "
                                    + expert.getName() + " 正在发言..."));
                    String expertText = streamAgentResponse(expert, callbacks);
                    log.info("[{}] 发言: {} 字符", expert.getName(), expertText.length());
                }

                if (cancelled) break;

                callbacks.onEvent(new ConversationEvent.Hint(
                        roundTag + " " + coordinatorName + " 正在汇总本轮发言..."));
                String coordText = streamAgentResponse(coordinator, callbacks);
                log.info("[协调者] 汇总: {} 字符", coordText.length());

                if (coordText.contains(PLAN_COMPLETE_MARKER)) {
                    log.info("协调者宣布讨论完成（第 {} 轮）", round);
                    callbacks.onEvent(new ConversationEvent.Hint(
                            roundTag + " " + coordinatorName + " 宣布讨论达成共识"));
                    break;
                }

                callbacks.onEvent(new ConversationEvent.Hint(roundTag + " 本轮讨论完成"));
            }
        } catch (Exception e) {
            log.error("MsgHub 讨论过程中出错", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 流式调用智能体并收集完整响应文本，同时把事件流转译为 {@link ConversationEvent}。
     *
     * @return 智能体的完整回复文本
     */
    private String streamAgentResponse(ReActAgent agent, ConversationCallbacks callbacks) {
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
                    .blockLast();
        } catch (Exception e) {
            if (!cancelled) {
                log.error("规划模式智能体 [{}] 流式调用异常", agentName, e);
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
                if (agent != null && selected.size() < maxExperts) {
                    selected.add(agent);
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
        cancel();
        log.info("规划模式服务已关闭");
    }
}
