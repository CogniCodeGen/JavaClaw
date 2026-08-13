package com.javaclaw.chat;

import com.javaclaw.agent.PricingTable;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationOptions;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Mode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coordinates a conversation turn and isolates late callbacks after cancellation. */
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
    private final Supplier<ModeRegistry> modes;
    private final BooleanSupplier rebuilding;
    private final Host host;
    private final ChatTurnOutcomeHandler outcomes;
    private final ChatTurnEventRouter events;
    private final ChatReplyExporter replyExporter;

    private int generation;
    private boolean streamingActive;
    private ChatSession streamingSession;
    private ChatActiveTurn activeTurn;

    ChatTurnController(
            FxDispatcher fx,
            TaskScope backgroundTasks,
            ChatComposerController composer,
            ChatModeController modeBar,
            ThinkingPanelController thinking,
            ChatStatusController status,
            ChatStreamRenderer renderer,
            ChatNavigationController navigation,
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
        this.modes = Objects.requireNonNull(modes, "modes");
        this.rebuilding = Objects.requireNonNull(rebuilding, "rebuilding");
        this.host = Objects.requireNonNull(host, "host");
        this.outcomes = new ChatTurnOutcomeHandler(thinking, renderer, host);
        this.replyExporter = new ChatReplyExporter(backgroundTasks, host::ownerWindow);
        this.events = new ChatTurnEventRouter(
                renderer,
                thinking,
                host,
                outcomes::loopDetected,
                this::updateMetrics,
                clarification -> {
                    host.appendClarification(clarification.reason(), clarification.question());
                    stop(CancellationReason.MODE_SWITCH, false, StopPolicy.DISCARD_AND_INVALIDATE);
                },
                () -> streamingSession != null && streamingSession != host.currentSession());
    }

    void sendFromComposer() {
        if (rebuilding.getAsBoolean()) return;
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

        ChatTurnRequest parsed = ChatTurnRequest.parse(userText);
        if (parsed == null) {
            host.addStaticMessage(ChatMessage.Role.SYSTEM,
                    "用法：/研讨 <问题> — 触发研讨模式多智能体讨论");
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

    void setTransitionBlocked(boolean blocked) {
        composer.setBlocked(blocked);
    }

    void stop() {
        stop(CancellationReason.USER_REQUEST, true, StopPolicy.PRESERVE_PARTIAL);
    }

    void stop(CancellationReason reason, boolean showFeedback, StopPolicy policy) {
        if (rebuilding.getAsBoolean() && policy == StopPolicy.PRESERVE_PARTIAL) {
            log.info("服务重建进行中，忽略停止请求（重建收尾会自行恢复输入）");
            return;
        }
        ChatActiveTurn turn = activeTurn;
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
        var priorMessages = ChatConversationHistory.snapshot(session);
        host.addUserMessage(text, attachments);
        composer.clearInput();
        composer.clearAttachments();
        setInputEnabled(false);
        streamingSession = session;
        composer.setThinkingVisible(true);
        int turnGeneration = generation;
        ChatActiveTurn turn = new ChatActiveTurn(turnGeneration, targetModeId);
        activeTurn = turn;
        thinking.startNewStream();

        String sessionId = session == null ? null : session.getId();
        fx.dispatchLater(() -> startMode(
                turn, text, attachments, sessionId, priorMessages));
    }

    private void startMode(
            ChatActiveTurn turn,
            String text,
            List<File> attachments,
            String sessionId,
            List<ConversationMessage> priorMessages) {
        if (generation != turn.generation || activeTurn != turn) {
            return;
        }
        host.saveChatHistory();
        renderer.createMessage(
                this::regenerate, replyExporter::save, host::deleteAssistantMessage);
        Mode mode = modes.get().getById(turn.modeId).orElse(null);
        if (!(mode instanceof ConversationMode conversationMode)) {
            consumeTerminal(turn.generation, ConversationOutcome.failed(
                    new IllegalStateException("模式未注册: " + turn.modeId)));
            return;
        }
        PlanProfile profile = "plan".equals(turn.modeId)
                ? modeBar.planProfile() : PlanProfile.AUTO;
        try {
            ConversationHandle handle = conversationMode.start(
                    new ConversationRequest(text, attachments, sessionId,
                            new ConversationOptions(profile), priorMessages),
                    callbacks(turn.generation));
            if (!handle.isTerminal() && generation == turn.generation && activeTurn == turn) {
                turn.handle = handle;
            }
        } catch (Throwable failure) {
            consumeTerminal(turn.generation, ConversationOutcome.failed(failure));
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
        if (generation == turnGeneration) events.route(event);
    }

    private void consumeTerminal(int turnGeneration, ConversationOutcome outcome) {
        if (generation != turnGeneration) return;
        ChatActiveTurn turn = activeTurn;
        if (turn != null && turn.generation == turnGeneration) {
            turn.handle = null;
        }
        boolean plan = isPlanStream();
        ChatSession target = targetSession();
        TurnMetrics metrics = metrics();
        switch (outcome) {
            case ConversationOutcome.Completed ignored -> outcomes.complete(
                    plan, target, metrics, () -> finishUi(turn, CompletionKind.SUCCEEDED));
            case ConversationOutcome.Cancelled value -> {
                if (turn != null) turn.deliveryState = DeliveryState.CANCELLED;
                outcomes.cancel(value, plan, target, metrics,
                        () -> finishUi(turn, CompletionKind.CANCELLED));
            }
            case ConversationOutcome.Failed value -> {
                if (turn != null) turn.deliveryState = DeliveryState.FAILED;
                outcomes.fail(value.error(), plan, target, metrics,
                        () -> finishUi(turn, CompletionKind.FAILED));
            }
        }
    }

    private void finishUi(ChatActiveTurn turn, CompletionKind kind) {
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
            ChatActiveTurn turn = activeTurn;
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

    private void updateMetrics(ConversationEvent.Usage usage) {
        ChatActiveTurn turn = activeTurn;
        if (turn == null) return;
        turn.inputTokens += Math.max(0, usage.inputTokens());
        turn.outputTokens += Math.max(0, usage.outputTokens());
        double cost = PricingTable.estimateCostCny(
                status.modelName(), turn.inputTokens, turn.outputTokens);
        thinking.updateMetrics(turn.inputTokens, turn.outputTokens,
                TokenTracker.formatCostCny(cost));
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

    private enum CompletionKind { SUCCEEDED, FAILED, CANCELLED }
}
