package com.javaclaw.chat;

import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.ui.javafx.loop.LoopStatusView;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Owns chat-session selection, persistence snapshots, and dynamic transcript resources.
 *
 * <p>Mutation is confined to the JavaFX application thread. Persistence receives immutable list
 * snapshots through the supplied managed executor. Workspace changes must call
 * {@link #reloadWorkspace()} so no old workspace path or message index remains cached.</p>
 */
final class ChatSessionCoordinator implements ChatTurnController.Host, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionCoordinator.class);

    private final Executor persistence;
    private final ChatHistoryManager history;
    private final ChatSessionController transcript;
    private final ChatComposerController composer;
    private final ThinkingPanelController thinking;
    private final SidebarController sidebar;
    private final ChatMessageRowFactory messageRows;
    private final ChatInlineImageRenderer inlineImages;
    private final ClarificationCardFactory clarificationCards;
    private final ChatStreamRenderer streamRenderer;
    private final ChatStatusController status;
    private final Supplier<ChatService> chatService;
    private final Supplier<PlanModeService> planModeService;
    private final Supplier<String> modelName;
    private final Supplier<Window> owner;
    private final Predicate<String> rejectWhileRebuilding;

    private final List<ChatSession> sessions = new ArrayList<>();
    private final List<Node> suspendedStreamNodes = new ArrayList<>();
    private ChatSession currentSession;
    private ChatTurnController turns;

    ChatSessionCoordinator(
            Executor persistence,
            ChatHistoryManager history,
            ChatSessionController transcript,
            ChatComposerController composer,
            ThinkingPanelController thinking,
            SidebarController sidebar,
            ChatMessageRowFactory messageRows,
            ChatInlineImageRenderer inlineImages,
            ClarificationCardFactory clarificationCards,
            ChatStreamRenderer streamRenderer,
            ChatStatusController status,
            Supplier<ChatService> chatService,
            Supplier<PlanModeService> planModeService,
            Supplier<String> modelName,
            Supplier<Window> owner,
            Predicate<String> rejectWhileRebuilding) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.history = Objects.requireNonNull(history, "history");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.sidebar = Objects.requireNonNull(sidebar, "sidebar");
        this.messageRows = Objects.requireNonNull(messageRows, "messageRows");
        this.inlineImages = Objects.requireNonNull(inlineImages, "inlineImages");
        this.clarificationCards = Objects.requireNonNull(clarificationCards, "clarificationCards");
        this.streamRenderer = Objects.requireNonNull(streamRenderer, "streamRenderer");
        this.status = Objects.requireNonNull(status, "status");
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.planModeService = Objects.requireNonNull(planModeService, "planModeService");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.rejectWhileRebuilding = Objects.requireNonNull(
                rejectWhileRebuilding, "rejectWhileRebuilding");
    }

    void bindTurns(ChatTurnController turns) {
        if (this.turns != null) {
            throw new IllegalStateException("ChatTurnController 已绑定");
        }
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    void load() {
        transcript.enterAtTail();
        List<ChatSession> loaded = history.loadSessionIndex();
        sessions.clear();
        List<ChatSession> valid = loaded.stream()
                .filter(session -> history.hasSessionMessages(session.getId()))
                .toList();
        if (valid.isEmpty()) {
            currentSession = new ChatSession("新的对话");
            sessions.add(currentSession);
            history.saveSessionIndex(sessions);
            sidebar.addSession(currentSession, true);
            addWelcomeMessage();
        } else {
            sessions.addAll(valid);
            if (valid.size() != loaded.size()) {
                history.saveSessionIndex(sessions);
            }
            for (int index = 0; index < sessions.size(); index++) {
                sidebar.addSession(sessions.get(index), index == 0);
            }
            currentSession = sessions.getFirst();
            List<ChatMessage> messages = history.loadSessionMessages(currentSession.getId());
            currentSession.getMessages().addAll(messages);
            messages.forEach(this::renderPersistedMessage);
            chatService.get().loadSession(currentSession.getId());
        }
        status.refreshTitle();
    }

    void newSession() {
        if (rejectWhileRebuilding.test("新建会话")) return;
        transcript.enterAtTail();
        ChatSession activeStream = turns.streamingSession();
        boolean running = turns.isStreaming() && activeStream != null;
        saveCurrentSession();
        if (running && currentSession == activeStream) {
            suspendTranscript();
        } else if (!running) {
            if (turns.isStreaming()) {
                turns.stop(CancellationReason.SESSION_SWITCH, false,
                        ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
            }
            if (currentSession != null) chatService.get().saveSession(currentSession.getId());
            clearRuntimeHistory();
        }
        removeEmptyCurrentSession();
        currentSession = new ChatSession("新的对话");
        sessions.addFirst(currentSession);
        sidebar.insertSessionAtTop(currentSession, true);
        disposeTranscript();
        composer.clearAttachments();
        if (!(turns.isStreaming() && turns.streamingSession() != null)) thinking.reset();
        addWelcomeMessage();
        status.refreshTitle();
        history.saveSessionIndex(sessions);
    }

    void switchSession(String targetId) {
        if (currentSession != null && currentSession.getId().equals(targetId)) return;
        if (rejectWhileRebuilding.test("切换会话")) return;
        ChatSession target = sessions.stream()
                .filter(session -> session.getId().equals(targetId))
                .findFirst().orElse(null);
        if (target == null) return;
        if (turns.isStreaming() && turns.streamingSession() == null) {
            turns.stop(CancellationReason.SESSION_SWITCH, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
        ChatSession activeStream = turns.streamingSession();
        boolean running = turns.isStreaming() && activeStream != null;
        saveCurrentSession();
        leaveCurrentSession(running, activeStream);
        composer.clearAttachments();
        currentSession = target;
        transcript.enterAtTail();
        enterTargetSession(target, running, activeStream);
        status.refreshTitle();
    }

    void deleteSession(String sessionId) {
        if (rejectWhileRebuilding.test("删除会话")) return;
        if (turns.streamingSession() != null
                && turns.streamingSession().getId().equals(sessionId)) {
            turns.stop(CancellationReason.SESSION_SWITCH, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
        sessions.removeIf(session -> session.getId().equals(sessionId));
        sidebar.removeSession(sessionId);
        history.deleteSession(sessionId);
        chatService.get().deleteSession(sessionId);
        if (currentSession != null && currentSession.getId().equals(sessionId)) {
            selectAfterCurrentDeletion();
        }
        history.saveSessionIndex(sessions);
    }

    void deleteSessions(List<String> sessionIds) {
        if (rejectWhileRebuilding.test("删除会话")) return;
        if (turns.streamingSession() != null
                && sessionIds.contains(turns.streamingSession().getId())) {
            turns.stop(CancellationReason.SESSION_SWITCH, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
        boolean currentDeleted = currentSession != null
                && sessionIds.contains(currentSession.getId());
        for (String id : sessionIds) {
            sessions.removeIf(session -> session.getId().equals(id));
            sidebar.removeSession(id);
            history.deleteSession(id);
            chatService.get().deleteSession(id);
        }
        if (currentDeleted) selectAfterCurrentDeletion();
        history.saveSessionIndex(sessions);
    }

    void clearCurrentHistory() {
        if (currentSession == null || rejectWhileRebuilding.test("清空对话")) return;
        if (turns.isStreaming() && turns.streamingSession() != null
                && currentSession != turns.streamingSession()) {
            addStaticMessage(ChatMessage.Role.SYSTEM, "另一会话正在生成回复，完成后再清空本会话");
            return;
        }
        turns.stop(CancellationReason.USER_REQUEST, false,
                ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        clearRuntimeHistory();
        status.resetSession();
        chatService.get().deleteSession(currentSession.getId());
        disposeTranscript();
        currentSession.getMessages().clear();
        currentSession.setTitle("新的对话");
        composer.clearAttachments();
        thinking.reset();
        addWelcomeMessage();
        status.refreshTitle();
        sidebar.updateSessionTitle(currentSession.getId(), currentSession.getTitle());
        saveCurrentSession();
    }

    void reloadWorkspace() {
        disposeTranscript();
        composer.clearAttachments();
        streamRenderer.resetReferences();
        thinking.reset();
        sidebar.clearSessions();
        sessions.clear();
        currentSession = null;
        load();
    }

    void saveCurrentSession() {
        saveSessionMessages(currentSession);
    }

    @Override
    public ChatSession currentSession() {
        return currentSession;
    }

    @Override
    public Window ownerWindow() {
        return owner.get();
    }

    @Override
    public void addUserMessage(String text, List<File> attachments) {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, text, attachments);
        currentSession.getMessages().add(message);
        transcript.addMessage(createMessageRow(message, List.of()).root());
    }

    @Override
    public void addStaticMessage(ChatMessage.Role role, String text) {
        ChatMessage message = new ChatMessage(role, text);
        currentSession.getMessages().add(message);
        transcript.addMessage(createMessageRow(message, List.of()).root());
    }

    @Override
    public void saveChatHistory() {
        saveCurrentSession();
        if (currentSession != null) chatService.get().saveSession(currentSession.getId());
    }

    @Override
    public void storeAssistantMessage(ChatSession target, ChatMessage message) {
        target.getMessages().add(message);
        target.autoTitle();
        if (target == currentSession) status.refreshTitle();
        sidebar.updateSessionTitle(target.getId(), target.getTitle());
        saveSessionMessages(target);
        chatService.get().saveSession(target.getId());
    }

    @Override
    public void storeSystemMessage(ChatSession target, String text) {
        if (target == currentSession) {
            addStaticMessage(ChatMessage.Role.SYSTEM, text);
        } else {
            target.getMessages().add(new ChatMessage(ChatMessage.Role.SYSTEM, text));
        }
        saveSessionMessages(target);
    }

    @Override
    public String lastUserMessage() {
        if (currentSession == null) return null;
        List<ChatMessage> messages = currentSession.getMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == ChatMessage.Role.USER
                    && message.getContent() != null && !message.getContent().isBlank()) {
                return message.getContent();
            }
        }
        return null;
    }

    @Override
    public void deleteAssistantMessage(AssistantMessageView message) {
        if (!transcript.removeContaining(message.root())) return;
        if (currentSession != null && !currentSession.getMessages().isEmpty()) {
            int last = currentSession.getMessages().size() - 1;
            if (currentSession.getMessages().get(last).getRole() == ChatMessage.Role.ASSISTANT) {
                currentSession.getMessages().remove(last);
                saveChatHistory();
            }
        }
        if (!streamRenderer.isActiveMessage(message)) message.close();
    }

    @Override
    public void adoptAssistantMessage(ChatMessage message) {
        message.setAdopted(true);
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(java.util.Map.of(
                    javafx.scene.input.DataFormat.PLAIN_TEXT, message.getContent()));
        }
        saveChatHistory();
    }

    @Override
    public void appendClarification(String reason, String question) {
        ChatMessage timestamp = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        ClarificationCardView card = clarificationCards.create(
                AgentConfig.AGENT_NAME, modelName.get(), timestamp.getFormattedTime(), reason, question);
        transcript.addMessage(card.root());
        String markdown = clarificationMarkdown(reason, question);
        if (currentSession != null) {
            currentSession.getMessages().add(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, markdown));
            saveSessionMessages(currentSession);
        }
        composer.focusInput();
    }

    @Override
    public void suspendStreamNode(Node node) {
        suspendedStreamNodes.add(node);
    }

    @Override
    public void disposeSuspendedStreamNodes() {
        suspendedStreamNodes.forEach(this::disposeNodeTree);
        suspendedStreamNodes.clear();
    }

    @Override
    public void finishBackgroundStream(ChatSession finishedSession) {
        if (finishedSession == null || currentSession == null || finishedSession == currentSession) {
            disposeSuspendedStreamNodes();
            return;
        }
        chatService.get().saveSession(finishedSession.getId());
        clearRuntimeHistory();
        chatService.get().loadSession(currentSession.getId());
        disposeSuspendedStreamNodes();
    }

    @Override
    public void close() {
        disposeTranscript();
        disposeSuspendedStreamNodes();
    }

    private void leaveCurrentSession(boolean running, ChatSession activeStream) {
        if (running && currentSession == activeStream) {
            suspendTranscript();
        } else if (running) {
            disposeTranscript();
        } else {
            if (currentSession != null) chatService.get().saveSession(currentSession.getId());
            clearRuntimeHistory();
            disposeTranscript();
            streamRenderer.resetReferences();
            thinking.reset();
        }
    }

    private void enterTargetSession(
            ChatSession target, boolean running, ChatSession activeStream) {
        if (running && target == activeStream) {
            transcript.setMessages(suspendedStreamNodes);
            suspendedStreamNodes.clear();
            composer.setThinkingText("助手正在思考中...");
            return;
        }
        if (!running) chatService.get().loadSession(target.getId());
        if (target.getMessages().isEmpty()) {
            target.getMessages().addAll(history.loadSessionMessages(target.getId()));
        }
        if (target.getMessages().isEmpty()) {
            addWelcomeMessage();
        } else {
            target.getMessages().forEach(this::renderPersistedMessage);
        }
    }

    private void selectAfterCurrentDeletion() {
        stopAndClearDeletedCurrent();
        if (sessions.isEmpty()) {
            currentSession = null;
            newSession();
        } else {
            currentSession = null;
            String firstId = sessions.getFirst().getId();
            sidebar.selectSession(firstId);
            switchSession(firstId);
        }
    }

    private void stopAndClearDeletedCurrent() {
        if (turns.isStreaming() && turns.streamingSession() == null) {
            turns.stop(CancellationReason.SESSION_SWITCH, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
        if (!(turns.isStreaming() && turns.streamingSession() != null)) clearRuntimeHistory();
    }

    private void removeEmptyCurrentSession() {
        if (currentSession == null || !currentSession.getMessages().isEmpty()) return;
        sessions.remove(currentSession);
        sidebar.removeSession(currentSession.getId());
    }

    private void suspendTranscript() {
        suspendedStreamNodes.clear();
        suspendedStreamNodes.addAll(transcript.detachMessages());
        composer.setThinkingText("其他会话正在后台生成回复…");
    }

    private void saveSessionMessages(ChatSession session) {
        if (session == null || session.getMessages().isEmpty()) return;
        String id = session.getId();
        List<ChatMessage> messages = List.copyOf(session.getMessages());
        List<ChatSession> index = List.copyOf(sessions);
        ChatHistoryManager targetHistory = history;
        persistence.execute(() -> {
            targetHistory.saveSessionMessages(id, messages);
            targetHistory.saveSessionIndex(index);
        });
    }

    private void addWelcomeMessage() {
        ChatMessage welcome = new ChatMessage(ChatMessage.Role.ASSISTANT,
                "你好！我是 JavaClaw 智能助手，拥有多智能体协作、任务规划和 Web 浏览能力。\n"
                        + "复杂问题我会自动分解任务并委派给专家处理。\n"
                        + "Web 智能体会自动管理 Playwright 浏览器。\n"
                        + "点击输入框左侧「+」按钮可以添加图片或文档附件。\n"
                        + "请问有什么可以帮助你的？");
        transcript.addMessage(messageRows.create(
                ChatMessageRowFactory.Variant.WELCOME,
                welcome,
                AgentConfig.AGENT_NAME,
                modelName.get(),
                "—",
                List.of(),
                inlineImages::enableZoom).root());
    }

    private void renderPersistedMessage(ChatMessage message) {
        List<ImageView> images = inlineImages.loadPersisted(message.getImagePaths());
        transcript.addMessage(createMessageRow(message, images).root());
    }

    private ChatMessageRowView createMessageRow(
            ChatMessage message, List<? extends Node> extraImages) {
        ChatMessageRowFactory.Variant variant = switch (message.getRole()) {
            case USER -> ChatMessageRowFactory.Variant.USER;
            case ASSISTANT -> ChatMessageRowFactory.Variant.ASSISTANT;
            case SYSTEM -> ChatMessageRowFactory.Variant.SYSTEM;
        };
        String metadata = message.getMetrics() == null ? "—" : ChatStreamRenderer.formatTurnMeta(
                message.getMetrics(), message.getDeliveryState());
        return messageRows.create(
                variant, message, AgentConfig.AGENT_NAME, modelName.get(), metadata,
                extraImages, inlineImages::enableZoom);
    }

    void clearRuntimeHistory() {
        chatService.get().clearHistory();
        planModeService.get().clearHistory();
    }

    private void disposeTranscript() {
        transcript.detachMessages().forEach(this::disposeNodeTree);
    }

    private void disposeNodeTree(Node node) {
        Object lifecycle = lifecycle(node);
        if (lifecycle instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                log.debug("关闭聊天动态视图失败", failure);
            }
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::disposeNodeTree);
        }
    }

    private static Object lifecycle(Node node) {
        if (!node.hasProperties()) return null;
        for (String key : List.of("loopStatusView", "loopDecisionView", "clarificationCardView",
                "chatMessageRowView", "assistantMessageView", "markdownBubble")) {
            Object value = node.getProperties().get(key);
            if (value instanceof LoopStatusView
                    || value instanceof LoopDecisionView
                    || value instanceof ClarificationCardView
                    || value instanceof ChatMessageRowView
                    || value instanceof AssistantMessageView
                    || value instanceof MarkdownBubble) {
                return value;
            }
        }
        return null;
    }

    private static String clarificationMarkdown(String reason, String question) {
        StringBuilder markdown = new StringBuilder("> 🤔 **需要您的澄清**\n>\n");
        if (reason != null && !reason.isBlank()) {
            markdown.append("> **原因**：").append(reason.replace("\n", "\n> "))
                    .append("\n>\n");
        }
        if (question != null && !question.isBlank()) {
            markdown.append("> **问题**：").append(question.replace("\n", "\n> "))
                    .append("\n");
        }
        return markdown.toString();
    }
}
