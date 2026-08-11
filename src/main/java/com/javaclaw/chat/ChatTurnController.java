package com.javaclaw.chat;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.PricingTable;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationOptions;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Mode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Coordinates one conversation turn from composer submission through its single terminal outcome.
 *
 * <p>Conversation callbacks may arrive on arbitrary threads and are marshalled through
 * {@link FxDispatcher}. Destructive cancellation invalidates the generation before invoking the
 * handle, so synchronous terminal callbacks cannot resurrect deleted UI or history. The controller
 * owns no workspace service permanently; suppliers are resolved at each turn.</p>
 */
final class ChatTurnController {

    enum StopPolicy { PRESERVE_PARTIAL, DISCARD_AND_INVALIDATE }

    interface Host {
        ChatSession currentSession();

        javafx.stage.Window ownerWindow();

        void addUserMessage(String text, List<File> attachments);

        void addStaticMessage(ChatMessage.Role role, String text);

        void saveChatHistory();

        void storeAssistantMessage(ChatSession target, ChatMessage message);

        void storeSystemMessage(ChatSession target, String text);

        String lastUserMessage();

        void deleteAssistantMessage(AssistantMessageView message);

        void adoptAssistantMessage(ChatMessage message);

        void appendClarification(String reason, String question);

        void suspendStreamNode(Node node);

        void disposeSuspendedStreamNodes();

        void finishBackgroundStream(ChatSession finishedSession);
    }

    private static final Logger log = LoggerFactory.getLogger(ChatTurnController.class);

    private final FxDispatcher fx;
    private final TaskScope backgroundTasks;
    private final ChatComposerController composer;
    private final ChatModeController modeBar;
    private final ThinkingPanelController thinking;
    private final ChatStatusController status;
    private final ChatStreamRenderer renderer;
    private final ChatNavigationController navigation;
    private final Supplier<AgentRuntime> runtime;
    private final Supplier<ModeRegistry> modes;
    private final BooleanSupplier rebuilding;
    private final Host host;

    private int generation;
    private boolean streamingActive;
    private ChatSession streamingSession;
    private ActiveTurn activeTurn;

    ChatTurnController(
            FxDispatcher fx,
            TaskScope backgroundTasks,
            ChatComposerController composer,
            ChatModeController modeBar,
            ThinkingPanelController thinking,
            ChatStatusController status,
            ChatStreamRenderer renderer,
            ChatNavigationController navigation,
            Supplier<AgentRuntime> runtime,
            Supplier<ModeRegistry> modes,
            BooleanSupplier rebuilding,
            Host host) {
        this.fx = Objects.requireNonNull(fx, "fx");
        this.backgroundTasks = Objects.requireNonNull(backgroundTasks, "backgroundTasks");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.modeBar = Objects.requireNonNull(modeBar, "modeBar");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.status = Objects.requireNonNull(status, "status");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.modes = Objects.requireNonNull(modes, "modes");
        this.rebuilding = Objects.requireNonNull(rebuilding, "rebuilding");
        this.host = Objects.requireNonNull(host, "host");
    }

    void sendFromComposer() {
        String userText = composer.trimmedInput();
        if (userText.isEmpty() && !composer.hasAttachments()) {
            return;
        }
        if (userText.startsWith("/任务")) {
            composer.clearInput();
            navigation.createTask(userText.substring(3).trim());
            return;
        }
        if (userText.equals("/诊断") || userText.equals("/diagnostics")) {
            composer.clearInput();
            navigation.openDiagnostics();
            return;
        }

        ParsedRequest parsed = parseRequest(userText);
        if (parsed == null) {
            return;
        }
        List<File> attachments = composer.attachmentSnapshot();
        String targetMode = conversationTargetId(parsed.forcePlan());
        if (!attachments.isEmpty() && !supportsAttachments(targetMode)) {
            host.addStaticMessage(ChatMessage.Role.SYSTEM,
                    "当前模式不支持附件：请移除附件后再发送，或切回对话模式处理附件内容");
            composer.showInputError();
            return;
        }
        start(parsed.text(), attachments, targetMode);
    }

    boolean isStreaming() {
        return streamingActive;
    }

    ChatSession streamingSession() {
        return streamingSession;
    }

    boolean isPlanStream() {
        return activeTurn != null && "plan".equals(activeTurn.modeId);
    }

    void setInputEnabled(boolean enabled) {
        streamingActive = !enabled;
        composer.setStreaming(streamingActive);
        status.setStreaming(streamingActive);
    }

    void stop() {
        stop(CancellationReason.USER_REQUEST, true, StopPolicy.PRESERVE_PARTIAL);
    }

    void stop(CancellationReason reason, boolean showFeedback, StopPolicy policy) {
        if (rebuilding.getAsBoolean() && policy == StopPolicy.PRESERVE_PARTIAL) {
            log.info("服务重建进行中，忽略停止请求（重建收尾会自行恢复输入）");
            return;
        }
        ActiveTurn turn = activeTurn;
        ConversationHandle handle = turn == null ? null : turn.handle;
        if (policy == StopPolicy.DISCARD_AND_INVALIDATE) {
            discard(reason, handle, showFeedback);
            return;
        }
        if (handle != null) {
            if (handle.cancel(reason)) {
                if (showFeedback) composer.showInputError();
                return;
            }
            if (handle.isTerminal()) {
                return;
            }
        }
        discard(reason, null, showFeedback);
    }

    private void start(String text, List<File> attachments, String targetModeId) {
        ChatSession session = host.currentSession();
        host.addUserMessage(text, attachments);
        composer.clearInput();
        composer.clearAttachments();
        setInputEnabled(false);
        streamingSession = session;
        composer.setThinkingVisible(true);
        int turnGeneration = generation;
        ActiveTurn turn = new ActiveTurn(turnGeneration, targetModeId);
        activeTurn = turn;
        thinking.startNewStream();

        String sessionId = session == null ? null : session.getId();
        fx.dispatchLater(() -> startMode(turn, text, attachments, sessionId));
    }

    private void startMode(
            ActiveTurn turn, String text, List<File> attachments, String sessionId) {
        if (generation != turn.generation || activeTurn != turn) {
            return;
        }
        host.saveChatHistory();
        renderer.createMessage(this::regenerate, this::saveReply, host::deleteAssistantMessage);
        Mode mode = modes.get().getById(turn.modeId).orElse(null);
        if (!(mode instanceof ConversationMode conversationMode)) {
            onError(new IllegalStateException("模式未注册: " + turn.modeId));
            return;
        }
        PlanProfile profile = "plan".equals(turn.modeId)
                ? modeBar.planProfile() : PlanProfile.AUTO;
        try {
            ConversationHandle handle = conversationMode.start(
                    new ConversationRequest(text, attachments, sessionId,
                            new ConversationOptions(profile)),
                    callbacks(turn.generation));
            if (!handle.isTerminal() && generation == turn.generation && activeTurn == turn) {
                turn.handle = handle;
            }
        } catch (Throwable failure) {
            onError(failure);
        }
    }

    private ConversationCallbacks callbacks(int turnGeneration) {
        return new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                fx.dispatch(() -> consumeEvent(turnGeneration, event));
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                fx.dispatch(() -> consumeTerminal(turnGeneration, outcome));
            }
        };
    }

    private void consumeEvent(int turnGeneration, ConversationEvent event) {
        if (generation != turnGeneration) {
            return;
        }
        switch (event) {
            case ConversationEvent.Thinking value -> renderer.appendThinking(value.chunk());
            case ConversationEvent.Reply value -> renderer.appendReply(value.chunk());
            case ConversationEvent.ToolResult value -> renderer.appendSubAgent(
                    value.toolName(), value.result(), ChatStreamRenderer.ChunkKind.RESULT);
            case ConversationEvent.SubAgentThinking value -> renderer.appendSubAgent(
                    value.agentName(), value.chunk(), ChatStreamRenderer.ChunkKind.THINKING);
            case ConversationEvent.SubAgentReply value -> renderer.appendSubAgent(
                    value.agentName(), value.chunk(), ChatStreamRenderer.ChunkKind.REPLY);
            case ConversationEvent.Hint value -> renderer.appendPlanHint(value.text());
            case ConversationEvent.AgentStart value -> renderer.startPlanAgent(value.agentName());
            case ConversationEvent.AgentReply value -> renderer.appendPlanAgentReply(value.chunk());
            case ConversationEvent.Evaluation value ->
                    renderer.appendPlanHint(value.result().formatForDisplay());
            case ConversationEvent.LoopDetected value -> onLoopDetected(value.warning());
            case ConversationEvent.Usage value -> updateMetrics(value);
            case ConversationEvent.Progress value -> thinking.recordPipelineProgress(
                    value.stageId(), value.stageLabel(),
                    value.status() == null ? "running" : value.status().name(), value.detail());
            case ConversationEvent.Custom value -> consumeCustom(value);
        }
    }

    private void consumeCustom(ConversationEvent.Custom event) {
        if ("plan_final".equals(event.kind()) && event.payload() instanceof String draft) {
            renderer.setFinalPlanDraft(draft);
        } else if ("clarify_request".equals(event.kind())
                && event.payload() instanceof com.javaclaw.agent.clarify.ClarifyPayload value) {
            host.appendClarification(value.reason(), value.question());
            stop(CancellationReason.MODE_SWITCH, false, StopPolicy.DISCARD_AND_INVALIDATE);
        } else if (com.javaclaw.loop.LoopConstants.EVENT_STATUS_KIND.equals(event.kind())
                && event.payload() instanceof com.javaclaw.loop.model.LoopStatus value) {
            renderer.updateLoopStatus(
                    value,
                    streamingSession != null && streamingSession != host.currentSession(),
                    host::suspendStreamNode);
        } else {
            log.debug("收到自定义事件 [{}] {}", event.kind(), event.payload());
        }
    }

    private void consumeTerminal(int turnGeneration, ConversationOutcome outcome) {
        if (generation != turnGeneration) {
            return;
        }
        ActiveTurn turn = activeTurn;
        if (turn != null && turn.generation == turnGeneration) {
            turn.handle = null;
        }
        switch (outcome) {
            case ConversationOutcome.Completed ignored -> onComplete();
            case ConversationOutcome.Cancelled value -> onCancelled(value);
            case ConversationOutcome.Failed value -> onError(value.error());
        }
    }

    private void onComplete() {
        ActiveTurn turn = activeTurn;
        AssistantMessageView rendered = renderer.message();
        MarkdownBubble reply = renderer.activeReply();
        if (isPlanStream()) {
            renderer.finishPlanAgent();
        }
        renderer.revealReply();
        try {
            if (isPlanStream() && reply != null && reply.getLength() == 0) {
                renderer.hideReplyCard();
            } else if (!isPlanStream() && reply != null && reply.getLength() == 0) {
                reply.finishWith("[模型未返回有效回复]");
            }
            renderer.renderInlineReplyImages();
            String text = renderer.currentText(isPlanStream());
            ChatSession target = targetSession();
            if (text != null && target != null) {
                ChatMessage message = message(text, DeliveryState.COMPLETE, metrics());
                renderer.displayedImagePaths().forEach(message::addImagePath);
                if (rendered != null) {
                    rendered.enableAdoption(() -> host.adoptAssistantMessage(message));
                }
                host.storeAssistantMessage(target, message);
            }
        } catch (RuntimeException failure) {
            log.error("保存回复时发生错误", failure);
        } finally {
            finishUi(turn, CompletionKind.SUCCEEDED);
        }
    }

    private void onError(Throwable error) {
        log.error("流式输出发生错误", error);
        ActiveTurn turn = activeTurn;
        if (turn != null) turn.deliveryState = DeliveryState.FAILED;
        if (isPlanStream()) renderer.finishPlanAgent();
        thinking.endStreamFailed();
        try {
            String detail = runtime.get().extractErrorMessage(error);
            String errorMessage = "调用失败: " + detail;
            ChatSession target = targetSession();
            String partial = renderer.currentText(isPlanStream());
            if (partial != null && !partial.isBlank() && target != null) {
                String failedText = partial + "\n\n> ⚠ 失败：" + detail;
                finishRenderedText(failedText);
                host.storeAssistantMessage(
                        target, message(failedText, DeliveryState.FAILED, metrics()));
            } else if (target != null && target != host.currentSession()) {
                host.storeSystemMessage(target, errorMessage);
            } else {
                renderer.hideReplyCard();
                host.addStaticMessage(ChatMessage.Role.SYSTEM, errorMessage);
            }
        } catch (RuntimeException displayFailure) {
            log.error("显示错误信息时发生异常", displayFailure);
        } finally {
            finishUi(turn, CompletionKind.FAILED);
        }
    }

    private void onCancelled(ConversationOutcome.Cancelled cancelled) {
        log.info("流式输出已取消 — reason={}, userInitiated={}",
                cancelled.reason(), cancelled.userInitiated());
        ActiveTurn turn = activeTurn;
        if (turn != null) turn.deliveryState = DeliveryState.CANCELLED;
        if (isPlanStream()) renderer.finishPlanAgent();
        renderer.revealReply();
        try {
            String partial = renderer.currentText(isPlanStream());
            ChatSession target = targetSession();
            if (partial != null && !partial.isBlank() && target != null) {
                String stoppedText = partial + "\n\n> ⏹ 已停止";
                finishRenderedText(stoppedText);
                host.storeAssistantMessage(
                        target, message(stoppedText, DeliveryState.CANCELLED, metrics()));
            } else {
                renderer.hideReplyCard();
            }
            renderer.markLoopCancelled();
        } finally {
            finishUi(turn, CompletionKind.CANCELLED);
        }
    }

    private void onLoopDetected(String warning) {
        log.warn("循环检测触发: {}", warning);
        MarkdownBubble reply = renderer.activeReply();
        if (reply == null) {
            host.addStaticMessage(ChatMessage.Role.SYSTEM, warning);
        } else if (reply.getLength() == 0) {
            reply.finishWith("[循环中断] " + warning);
        } else {
            reply.appendText("\n\n[循环中断] " + warning);
        }
    }

    private void finishUi(ActiveTurn turn, CompletionKind kind) {
        DeliveryState state = turn == null ? DeliveryState.COMPLETE : turn.deliveryState;
        renderer.clear(turn == null ? TurnMetrics.ZERO : turn.metrics(), state);
        switch (kind) {
            case SUCCEEDED -> thinking.endStream();
            case FAILED -> { }
            case CANCELLED -> thinking.endStreamCancelled();
        }
        composer.setThinkingVisible(false);
        composer.setThinkingText("助手正在思考中...");
        setInputEnabled(true);
        ChatSession finished = streamingSession;
        streamingSession = null;
        activeTurn = null;
        host.finishBackgroundStream(finished);
        composer.focusInput();
    }

    private void discard(
            CancellationReason reason, ConversationHandle handle, boolean showFeedback) {
        invalidateBeforeCancel(() -> {
            generation++;
            ActiveTurn turn = activeTurn;
            activeTurn = null;
            streamingSession = null;
            AssistantMessageView abandoned = renderer.abandon(
                    turn == null ? TurnMetrics.ZERO : turn.metrics(), DeliveryState.CANCELLED);
            host.disposeSuspendedStreamNodes();
            if (abandoned != null) {
                abandoned.root().setVisible(false);
                abandoned.root().setManaged(false);
                abandoned.close();
            }
            composer.setThinkingVisible(false);
            composer.setThinkingText("助手正在思考中...");
            thinking.endStreamCancelled();
            setInputEnabled(true);
        }, handle, reason);
        if (showFeedback) composer.showInputError();
    }

    static void invalidateBeforeCancel(
            Runnable invalidation, ConversationHandle handle, CancellationReason reason) {
        invalidation.run();
        if (handle == null) {
            return;
        }
        try {
            handle.cancel(reason);
        } catch (RuntimeException failure) {
            log.warn("取消已失效的对话运行失败（旧回调已隔离）: {}", failure.getMessage());
        }
    }

    private void regenerate() {
        String previous = host.lastUserMessage();
        if (previous != null) {
            composer.replaceInput(previous);
            sendFromComposer();
        }
    }

    private void saveReply(String text) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存回复到文件");
        chooser.setInitialFileName("reply.md");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md"),
                new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showSaveDialog(host.ownerWindow());
        if (file == null) return;
        backgroundTasks.submit(TaskSpec.io("export-assistant-reply"), context -> {
            Files.writeString(file.toPath(), text);
            return null;
        }).completion().whenComplete((ignored, failure) -> {
            if (failure != null) log.error("保存回复到文件失败", failure);
        });
    }

    private void updateMetrics(ConversationEvent.Usage usage) {
        ActiveTurn turn = activeTurn;
        if (turn == null) return;
        turn.inputTokens += Math.max(0, usage.inputTokens());
        turn.outputTokens += Math.max(0, usage.outputTokens());
        double cost = PricingTable.estimateCostCny(
                status.modelName(), turn.inputTokens, turn.outputTokens);
        thinking.updateMetrics(turn.inputTokens, turn.outputTokens,
                TokenTracker.formatCostCny(cost));
    }

    private void finishRenderedText(String text) {
        MarkdownBubble reply = renderer.activeReply();
        if (!isPlanStream() && reply != null) {
            reply.finishWith(text);
        } else if (isPlanStream() && renderer.planAgentBlock() != null) {
            renderer.planAgentBlock().bubble().finishWith(text);
        }
    }

    private ParsedRequest parseRequest(String userText) {
        if (!(userText.startsWith("/plan ")
                || userText.startsWith("/规划 ")
                || userText.startsWith("/研讨 "))) {
            return new ParsedRequest(userText, false);
        }
        int prefixLength = userText.startsWith("/plan ") ? 6 : 4;
        String text = userText.substring(prefixLength).trim();
        if (text.isEmpty()) {
            host.addStaticMessage(ChatMessage.Role.SYSTEM,
                    "用法：/研讨 <问题> — 触发研讨模式多智能体讨论");
            return null;
        }
        return new ParsedRequest(text, true);
    }

    private String conversationTargetId(boolean forcePlan) {
        return forcePlan ? "plan" : modeBar.selectedModeId();
    }

    private boolean supportsAttachments(String modeId) {
        return modes.get().getById(modeId)
                .map(mode -> mode.capabilities().supportsAttachments())
                .orElse(true);
    }

    private ChatSession targetSession() {
        return streamingSession != null ? streamingSession : host.currentSession();
    }

    private TurnMetrics metrics() {
        return activeTurn == null ? TurnMetrics.ZERO : activeTurn.metrics();
    }

    private static ChatMessage message(
            String text, DeliveryState state, TurnMetrics metrics) {
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, text);
        message.setDeliveryState(state);
        message.setMetrics(metrics);
        return message;
    }

    private record ParsedRequest(String text, boolean forcePlan) { }

    private enum CompletionKind { SUCCEEDED, FAILED, CANCELLED }

    private static final class ActiveTurn {
        private final int generation;
        private final String modeId;
        private final long startedAtNanos = System.nanoTime();
        private long inputTokens;
        private long outputTokens;
        private DeliveryState deliveryState = DeliveryState.COMPLETE;
        private volatile ConversationHandle handle;

        private ActiveTurn(int generation, String modeId) {
            this.generation = generation;
            this.modeId = modeId;
        }

        private TurnMetrics metrics() {
            long duration = Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
            return new TurnMetrics(inputTokens, outputTokens, duration);
        }
    }
}
