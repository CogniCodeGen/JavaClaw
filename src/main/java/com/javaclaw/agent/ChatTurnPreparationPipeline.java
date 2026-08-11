package com.javaclaw.agent;

import com.javaclaw.agent.goal.GoalDecomposition;
import com.javaclaw.agent.goal.GoalManager;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.application.turn.TurnPipeline;
import com.javaclaw.application.turn.TurnStage;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Builds the ordered, fail-fast preparation pipeline for one normal chat turn.
 *
 * <p>The collaborator is reusable and keeps no per-turn state. Each invocation creates a private
 * state object, emits stable stage IDs and returns the stream only after all preparation stages
 * succeed.</p>
 */
final class ChatTurnPreparationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnPreparationPipeline.class);

    private final AgentRuntime runtime;
    private final MemoryService memory;
    private final ToolRouter router;
    private final GoalManager goals;
    private final StreamOptions streamOptions;
    private final Supplier<ReActAgent> orchestrator;
    private final BiConsumer<RoutingResult, GoalDecomposition> orchestratorRebuilder;

    ChatTurnPreparationPipeline(
            AgentRuntime runtime,
            MemoryService memory,
            ToolRouter router,
            GoalManager goals,
            StreamOptions streamOptions,
            Supplier<ReActAgent> orchestrator,
            BiConsumer<RoutingResult, GoalDecomposition> orchestratorRebuilder) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.router = router;
        this.goals = goals;
        this.streamOptions = Objects.requireNonNull(streamOptions, "streamOptions");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.orchestratorRebuilder = Objects.requireNonNull(
                orchestratorRebuilder, "orchestratorRebuilder");
    }

    Flux<Event> prepare(
            String userInput,
            String processedInput,
            List<File> attachments,
            boolean visionPrepared,
            ConversationCallbacks callbacks,
            String previousAssistantReply,
            AtomicReference<CorrectionTurnContext> correctionContext) {
        TurnState state = new TurnState(
                userInput, processedInput, attachments, visionPrepared);
        return create(callbacks, previousAssistantReply, correctionContext)
                .execute(state).stream();
    }

    private TurnPipeline<TurnState> create(
            ConversationCallbacks callbacks,
            String previousAssistantReply,
            AtomicReference<CorrectionTurnContext> correctionContext) {
        return new TurnPipeline<>(List.of(
                new TurnStage<>("correction", state -> prepareCorrection(
                        state, callbacks, previousAssistantReply, correctionContext)),
                new TurnStage<>("vision", state -> prepareVision(state, callbacks)),
                new TurnStage<>("routing", state -> prepareRouting(state, callbacks)),
                new TurnStage<>("goal", state -> prepareGoals(state, callbacks)),
                new TurnStage<>("rag", state -> enrichKnowledge(state, callbacks)),
                new TurnStage<>("context", state -> prepareContextBudget(state, callbacks)),
                new TurnStage<>("build", state -> buildTurnMessage(state, callbacks)),
                new TurnStage<>("stream", state -> beginTurnStream(state, callbacks))));
    }

    private void prepareCorrection(
            TurnState state,
            ConversationCallbacks callbacks,
            String previousAssistantReply,
            AtomicReference<CorrectionTurnContext> correctionContext) {
        CorrectionTurnContext correction = memory.prepareCorrectionTurn(
                state.userInput, previousAssistantReply);
        correctionContext.set(correction);
        if (correction.newlyApplied() == null) {
            progress(callbacks, "correction", "纠错记忆",
                    ConversationEvent.Progress.Status.SKIPPED, "未检测到显式纠错");
            return;
        }
        String detail = correction.newlyApplied().status
                == com.javaclaw.memory.model.CorrectionRecord.Status.ACTIVE
                ? "已更新长期事实" : "已标记争议，等待核验";
        progress(callbacks, "correction", "纠错记忆",
                ConversationEvent.Progress.Status.DONE, detail);
        callbacks.onEvent(new ConversationEvent.Hint(
                "[记忆] 已记录用户显式纠错，本轮优先采用纠错上下文"));
    }

    private void prepareVision(TurnState state, ConversationCallbacks callbacks) {
        if (state.visionPrepared) return;
        if (!runtime.hasImageAttachment(state.attachments)) {
            progress(callbacks, "vision", "视觉预处理",
                    ConversationEvent.Progress.Status.SKIPPED, "无图片附件");
            return;
        }
        progress(callbacks, "vision", "视觉预处理",
                ConversationEvent.Progress.Status.RUNNING, "正在分析图片内容…");
        callbacks.onEvent(new ConversationEvent.Hint("[视觉] 正在分析图片内容..."));
        String description = runtime.getVisionPreprocessor()
                .describe(state.userInput, state.attachments);
        if (description == null) {
            progress(callbacks, "vision", "视觉预处理",
                    ConversationEvent.Progress.Status.SKIPPED, "未生成描述，原图直传");
            return;
        }
        state.processedInput = "[附件图片分析]\n" + description
                + "\n\n[用户提问]\n" + state.userInput;
        state.attachments = state.attachments.stream()
                .filter(file -> !ChatMessage.isImageFile(file)).toList();
        progress(callbacks, "vision", "视觉预处理",
                ConversationEvent.Progress.Status.DONE, summarize(description, 60));
    }

    private void prepareRouting(TurnState state, ConversationCallbacks callbacks) {
        progress(callbacks, "intent", "意图识别",
                ConversationEvent.Progress.Status.RUNNING, "分析所需工具…");
        state.routing = routeTools(state.processedInput);
        if (router == null) {
            progress(callbacks, "intent", "意图识别",
                    ConversationEvent.Progress.Status.SKIPPED, "工具路由已禁用");
        } else if (state.routing.isFallback()) {
            progress(callbacks, "intent", "意图识别",
                    ConversationEvent.Progress.Status.DONE, "降级为全量工具");
        } else {
            progress(callbacks, "intent", "意图识别",
                    ConversationEvent.Progress.Status.DONE, describeRouting(state.routing));
        }
    }

    private void prepareGoals(TurnState state, ConversationCallbacks callbacks) {
        if (goals == null) {
            progress(callbacks, "goal", "目标分解",
                    ConversationEvent.Progress.Status.SKIPPED, "GEPA 目标分解未启用");
        } else {
            progress(callbacks, "goal", "目标分解",
                    ConversationEvent.Progress.Status.RUNNING, "拆解用户目标…");
            state.goals = goals.decompose(state.processedInput);
            boolean hasGoals = state.goals != null && state.goals.hasGoals();
            progress(callbacks, "goal", "目标分解",
                    hasGoals ? ConversationEvent.Progress.Status.DONE
                            : ConversationEvent.Progress.Status.SKIPPED,
                    hasGoals ? "拆解为 " + state.goals.getGoals().size() + " 个目标" : "无需拆解");
        }
        orchestratorRebuilder.accept(state.routing, state.goals);
    }

    private void enrichKnowledge(TurnState state, ConversationCallbacks callbacks) {
        progress(callbacks, "rag", "知识库检索",
                ConversationEvent.Progress.Status.RUNNING, "检索相关资料…");
        int originalLength = state.processedInput.length();
        state.processedInput = runtime.enrichWithKnowledge(state.processedInput);
        int added = state.processedInput.length() - originalLength;
        progress(callbacks, "rag", "知识库检索",
                added > 0 ? ConversationEvent.Progress.Status.DONE
                        : ConversationEvent.Progress.Status.SKIPPED,
                added > 0 ? "已注入 " + added + " 字符上下文" : "未启用或未选中文档");
    }

    private void prepareContextBudget(TurnState state, ConversationCallbacks callbacks) {
        progress(callbacks, "memory", "上下文整理",
                ConversationEvent.Progress.Status.RUNNING, "检查上下文预算…");
        boolean fits = runtime.getMemoryManager()
                .ensureContextBudget(state.processedInput.length(), 4096);
        if (!fits) {
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[提示] 会话历史较长，已自动压缩上下文以保证回复质量"));
        }
        progress(callbacks, "memory", "上下文整理",
                ConversationEvent.Progress.Status.DONE, fits ? "无需压缩" : "已自动压缩历史");
    }

    private void buildTurnMessage(TurnState state, ConversationCallbacks callbacks) {
        progress(callbacks, "build", "内容构建",
                ConversationEvent.Progress.Status.RUNNING, "组装多模态消息…");
        state.userMessage = runtime.buildUserMsg(state.processedInput, state.attachments);
        progress(callbacks, "build", "内容构建", ConversationEvent.Progress.Status.DONE, null);
    }

    private void beginTurnStream(TurnState state, ConversationCallbacks callbacks) {
        progress(callbacks, "orchestrate", "编排执行",
                ConversationEvent.Progress.Status.RUNNING, "调用主智能体…");
        state.stream = orchestrator.get().stream(state.userMessage, streamOptions);
    }

    private RoutingResult routeTools(String userInput) {
        if (router == null) return RoutingResult.fallbackAll();
        try {
            return router.route(userInput);
        } catch (Exception failure) {
            log.warn("工具路由异常，降级为全量: {}", failure.getMessage());
            return RoutingResult.fallbackAll();
        }
    }

    private static String describeRouting(RoutingResult routing) {
        int groupCount = routing.toolGroups() == null ? 0 : routing.toolGroups().size();
        int skillCount = routing.skillNames() == null ? 0 : routing.skillNames().size();
        return "命中 " + groupCount + " 个工具组"
                + (skillCount > 0 ? "，" + skillCount + " 项技能" : "");
    }

    private static String summarize(String text, int maxLength) {
        if (text == null) return null;
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLength
                ? oneLine : oneLine.substring(0, maxLength) + "…";
    }

    private static void progress(
            ConversationCallbacks callbacks,
            String stageId,
            String label,
            ConversationEvent.Progress.Status status,
            String detail) {
        ChatProgressEmitter.emit(callbacks, stageId, label, status, detail);
    }

    private static final class TurnState {
        private final String userInput;
        private final boolean visionPrepared;
        private String processedInput;
        private List<File> attachments;
        private RoutingResult routing;
        private GoalDecomposition goals;
        private Msg userMessage;
        private Flux<Event> stream;

        private TurnState(
                String userInput,
                String processedInput,
                List<File> attachments,
                boolean visionPrepared) {
            this.userInput = userInput;
            this.processedInput = processedInput;
            this.attachments = List.copyOf(attachments);
            this.visionPrepared = visionPrepared;
        }

        private Flux<Event> stream() {
            if (stream == null) throw new IllegalStateException("stream 阶段尚未执行");
            return stream;
        }
    }
}
