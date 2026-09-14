package com.javaclaw.desktop.shell;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.document.DocumentPreviewPane;
import com.javaclaw.desktop.document.SdkDocumentPreviewGateway;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.desktop.view.ChatSurface;
import com.javaclaw.desktop.view.PresentedItem;
import com.javaclaw.desktop.view.TranscriptPresenter;
import com.javaclaw.protocol.CanonicalJson;

/** 主壳的表现层资源 owner；保留原生待处理内容、输入和审批，只替换正文表面。 */
final class ShellWebSurfaces implements AutoCloseable {
    private final ChatSurface chat;
    private final DocumentPreviewPane documents;
    private final ShellSidePanels sidePanels;
    private final VBox pending = new VBox();
    private final VBox nativeTranscript = new VBox();
    private final ListView<ShellTranscriptRow> summary = new ListView<>();
    private final ListView<ItemEnvelope> legacy;
    private final TranscriptPresenter nativeFormatter = new TranscriptPresenter(new CanonicalJson());
    private final ChangeListener<Boolean> nativeVisibility = (observable, before, showing) -> {
        if (showing) {
            renderNative(true);
        }
    };
    private final Button earlier =
            new PlatformComponentFactory().action("加载更早消息", ActionStyle.GHOST, ActionSize.COMPACT);
    private final Button latest =
            new PlatformComponentFactory().action("回到最新消息", ActionStyle.GHOST, ActionSize.COMPACT);
    private final com.javaclaw.desktop.DesktopNotificationSubscription invalidations;
    private Optional<WorkspaceId> workspace = Optional.empty();
    private Optional<Instant> connection = Optional.empty();
    private TranscriptState transcript = TranscriptState.empty();
    private List<ChatSurface.TemporaryMessage> temporary = List.of();
    private List<ItemEnvelope> nativeItems = List.of();
    private List<ItemHistoryEntry> nativeHistory = List.of();
    private List<ShellTranscriptRow> committedRows = List.of();
    private final HashSet<String> committedIds = new HashSet<>();
    private boolean nativeFollowing = true;
    private String nativeScope = "empty";
    private final Map<String, LinkedHashSet<String>> expanded = new LinkedHashMap<>(64, 0.75f, true);
    private final BooleanProperty retryReady = new SimpleBooleanProperty();
    private final BooleanProperty restoreAllowed = new SimpleBooleanProperty();
    private BiConsumer<String, String> outgoingActions = (action, id) -> {};

    ShellWebSurfaces(
            DesktopPresenter presenter,
            VBox progress,
            StackPane host,
            ListView<ItemEnvelope> legacy,
            ShellSidePanels sidePanels) {
        this.sidePanels = sidePanels;
        this.legacy = legacy;
        documents = new DocumentPreviewPane(new SdkDocumentPreviewGateway(presenter), this::external);
        documents.onClosed(() -> {
            selectDocument(false);
            sidePanels.documentClosed();
        });
        invalidations = presenter.subscribeNotifications(notification -> {
            if (notification instanceof com.javaclaw.client.ServerNotification.DocumentInvalidated event) {
                documents.invalidate(event.event().handleId(), event.event().reasonCode());
            }
        });
        var contents = List.copyOf(progress.getChildren());
        progress.getChildren().clear();
        pending.getChildren().setAll(contents);
        PlatformComponentFactory components = new PlatformComponentFactory();
        Button pendingTab = components.action("待处理", ActionStyle.GHOST, ActionSize.COMPACT);
        Button documentTab = components.action("文档", ActionStyle.GHOST, ActionSize.COMPACT);
        pendingTab.setOnAction(event -> selectDocument(false));
        documentTab.setOnAction(event -> selectDocument(true));
        progress.getChildren().addAll(new HBox(8, pendingTab, documentTab), pending, documents);
        VBox.setVgrow(pending, Priority.ALWAYS);
        VBox.setVgrow(documents, Priority.ALWAYS);
        selectDocument(false);
        sidePanels.onPending(() -> selectDocument(false));
        summary.setId("transcriptSummary");
        summary.getStyleClass().addAll("message-scroll", "transcript-list");
        summary.setMaxWidth(1000);
        summary.setCellFactory(ignored -> new ShellSummaryCell(
                this::preview,
                () -> workspace,
                id -> expanded.getOrDefault(nativeScope, new LinkedHashSet<>()).contains(id),
                this::toggleDetails,
                (action, id) -> outgoingActions.accept(action, id),
                retryReady,
                restoreAllowed));
        host.getChildren().clear();
        earlier.setOnAction(event -> presenter.loadEarlierTranscript());
        installNativeFollowing(presenter);
        StackPane nativeMessages = new StackPane(legacy, summary);
        nativeTranscript.getChildren().setAll(new HBox(8, earlier, latest), nativeMessages);
        VBox.setVgrow(nativeMessages, Priority.ALWAYS);
        chat = new ChatSurface(
                nativeTranscript,
                this::preview,
                this::external,
                presenter::loadEarlierTranscript,
                presenter::followTranscript);
        // Host 同步显露简版时补入最新快照；健康 WebView 不维护隐藏列表，也不依赖下一次服务端通知。
        nativeTranscript.visibleProperty().addListener(nativeVisibility);
        host.getChildren().add(chat.node());
    }

    void onOutgoingAction(BiConsumer<String, String> handler) {
        outgoingActions = handler;
        chat.onOutgoingAction(handler);
    }

    void setOutgoingAvailability(boolean ready, boolean restore) {
        retryReady.set(ready);
        restoreAllowed.set(restore);
        chat.setOutgoingAvailability(ready, restore);
    }

    private void toggleDetails(String id) {
        LinkedHashSet<String> opened = expanded.computeIfAbsent(nativeScope, ignored -> new LinkedHashSet<>());
        while (expanded.size() > 64) {
            expanded.remove(expanded.keySet().iterator().next());
        }
        if (!opened.remove(id)) {
            opened.add(id);
        }
        while (opened.size() > 500) {
            opened.remove(opened.iterator().next());
        }
        // 点击只改变当前行的高度；保持 ListView 当前阅读位置，不发布跟随到底部的业务意图。
    }

    private Optional<DocumentReference> fullMessage(ItemEnvelope item, PresentedItem presented) {
        if (!CoreSchemas.MESSAGE.equals(item.schemaId()) || presented.body().length() <= 65_536) {
            return Optional.empty();
        }
        MessageRole role = new CanonicalJson()
                .decode(item.payload(), CorePayloads.Message.class)
                .role();
        if (role != MessageRole.USER && role != MessageRole.ASSISTANT) {
            return Optional.empty();
        }
        return workspace.map(value -> DocumentReference.message(value, item.id(), "body"));
    }

    private void installNativeFollowing(DesktopPresenter presenter) {
        visible(latest, false);
        latest.setOnAction(event -> {
            presenter.followTranscript(true);
            ListView<?> selected = summary.isVisible() ? summary : legacy;
            selected.scrollTo(selected.getItems().size() - 1);
        });
        for (ListView<?> list : List.of(summary, legacy)) {
            // 只观察真实输入事件，不监听 value/scrollTo；布局与程序跟随不得反向切换阅读策略。
            list.addEventFilter(ScrollEvent.SCROLL, event -> {
                if (event.getDeltaY() > 0) {
                    presenter.followTranscript(false);
                }
            });
            list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                switch (event.getCode()) {
                    case UP, PAGE_UP, HOME -> presenter.followTranscript(false);
                    default -> {}
                }
            });
            list.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (verticalScrollBar(event.getTarget())) {
                    presenter.followTranscript(false);
                }
            });
        }
    }

    private static boolean verticalScrollBar(Object target) {
        for (Node node = target instanceof Node value ? value : null; node != null; node = node.getParent()) {
            if (node instanceof ScrollBar bar) {
                return bar.getOrientation() == Orientation.VERTICAL;
            }
        }
        return false;
    }

    void render(DesktopState state) {
        transcript = state.transcript();
        nativeScope = state.threads()
                        .selectedWorkspace()
                        .map(Workspace::id)
                        .map(Object::toString)
                        .orElse("") + ":"
                + state.threads()
                        .selectedThread()
                        .map(thread -> thread.id().toString())
                        .orElse("");
        Optional<WorkspaceId> next = state.threads().selectedWorkspace().map(Workspace::id);
        if (!workspace.equals(next) || !connection.equals(state.connection().connectedAt())) {
            documents.clear();
            workspace = next;
            connection = state.connection().connectedAt();
        }
        if (next.isPresent()) {
            temporary = temporaryMessages(state);
            if (nativeTranscript.isVisible()) {
                renderNative(false);
            }
            chat.show(
                    state.threads()
                            .selectedThread()
                            .map(thread -> thread.id().toString())
                            .orElse("empty"),
                    next.orElseThrow(),
                    state.transcript().items(),
                    state.transcript().history(),
                    temporary,
                    state.transcript().hasEarlier(),
                    state.transcript().outgoings());
        } else {
            temporary = List.of();
            clearNative();
            chat.clear();
        }
    }

    private static List<ChatSurface.TemporaryMessage> temporaryMessages(DesktopState state) {
        List<ChatSurface.TemporaryMessage> messages = state.transcript().stream().stream()
                .flatMap(stream -> stream.messages().stream()
                        .filter(message -> visible(message, state.transcript()))
                        .map(message -> new ChatSurface.TemporaryMessage(
                                message.call().messageItemId().toString(),
                                message.text(),
                                message.state() == TurnStreamKind.CLOSED,
                                message.textOffsetUtf16(),
                                Optional.of(stream.turnId()),
                                activity(state, stream.turnId(), message.state()))))
                .filter(message -> !message.text().isEmpty() || message.activity() == ChatSurface.Activity.WAITING)
                .toList();
        Optional<ChatSurface.TemporaryMessage> placeholder = waitingPlaceholder(state);
        if (placeholder.isEmpty()) {
            return messages;
        }
        ArrayList<ChatSurface.TemporaryMessage> combined = new ArrayList<>(messages);
        combined.add(placeholder.orElseThrow());
        return List.copyOf(combined);
    }

    private static boolean visible(
            com.javaclaw.client.facade.TurnStreamSnapshot.Message message, TranscriptState transcript) {
        return message.state() != TurnStreamKind.COMMITTED
                || message.itemSequence()
                        .filter(sequence -> sequence > transcript.nextSequence())
                        .isPresent();
    }

    private static ChatSurface.Activity activity(DesktopState state, TurnId turnId, TurnStreamKind kind) {
        if (modelActivityBlocked(state, turnId)) {
            return ChatSurface.Activity.SETTLED;
        }
        return switch (kind) {
            case STARTED -> ChatSurface.Activity.WAITING;
            case TEXT_DELTA -> ChatSurface.Activity.STREAMING;
            case COMMITTED, CLOSED, TURN_FINISHED -> ChatSurface.Activity.SETTLED;
        };
    }

    private static boolean modelActivityBlocked(DesktopState state, TurnId turnId) {
        boolean generating = state.threads()
                .activeTurn()
                .filter(turn -> turn.id().equals(turnId))
                .map(AgentTurn::status)
                .filter(status -> status == TurnStatus.QUEUED || status == TurnStatus.RUNNING)
                .isPresent();
        boolean approval = state.interaction().pendingApprovals().stream()
                .anyMatch(record -> record.request().turnId().equals(turnId));
        boolean input = state.interaction().inputs().pendingRequests().stream()
                .anyMatch(record -> record.request().turnId().equals(turnId));
        return !generating || approval || input;
    }

    private static Optional<ChatSurface.TemporaryMessage> waitingPlaceholder(DesktopState state) {
        Optional<AgentTurn> active = state.threads().activeTurn();
        if (active.map(AgentTurn::status)
                        .filter(status -> status == TurnStatus.QUEUED || status == TurnStatus.RUNNING)
                        .isEmpty()
                || active.map(AgentTurn::id)
                        .filter(turnId -> modelActivityBlocked(state, turnId))
                        .isPresent()) {
            return Optional.empty();
        }
        AgentTurn turn = active.orElseThrow();
        boolean currentCall = state.transcript().stream()
                .filter(stream -> stream.turnId().equals(turn.id()))
                .map(stream -> stream.messages().stream().anyMatch(message -> visible(message, state.transcript())))
                .orElse(false);
        if (currentCall) {
            return Optional.empty();
        }
        return Optional.of(new ChatSurface.TemporaryMessage(
                activityId(turn.id()), "", false, 0, Optional.of(turn.id()), ChatSurface.Activity.WAITING));
    }

    private static String activityId(TurnId turnId) {
        return "activity:" + turnId;
    }

    private void renderNative(boolean revealed) {
        if (workspace.isEmpty()) {
            clearNative();
            return;
        }
        boolean itemsChanged = !nativeItems.equals(transcript.items());
        List<ShellTranscriptRow> rows = nativeRows();
        boolean projected = needsSummary(rows);
        boolean changed = !summary.getItems().equals(rows);
        if (changed) {
            summary.getItems().setAll(rows);
        }
        visible(summary, projected);
        visible(legacy, !projected);
        if (transcript.following() && (revealed || !nativeFollowing || (projected ? changed : itemsChanged))) {
            ListView<?> selected = projected ? summary : legacy;
            if (!selected.getItems().isEmpty()) {
                selected.scrollTo(selected.getItems().size() - 1);
            }
        }
        nativeFollowing = transcript.following();
        visible(earlier, transcript.hasEarlier());
        visible(latest, !transcript.following());
    }

    private boolean needsSummary(List<ShellTranscriptRow> rows) {
        return !transcript.history().isEmpty()
                || !temporary.isEmpty()
                || !transcript.outgoings().isEmpty()
                || rows.stream().anyMatch(row -> row.presented().collapsible());
    }

    private List<ShellTranscriptRow> nativeRows() {
        if (!nativeItems.equals(transcript.items()) || !nativeHistory.equals(transcript.history())) {
            rebuildCommittedRows();
        }
        List<ChatSurface.TemporaryMessage> visibleTemporary = nativeTemporary();
        List<OutgoingMessage> messages = transcript
                .outgoings()
                .subList(
                        Math.max(0, transcript.outgoings().size() - 500),
                        transcript.outgoings().size());
        int capacity = 500 - messages.size();
        int skipped = Math.max(0, committedRows.size() + visibleTemporary.size() - capacity);
        int committedSkipped = Math.min(skipped, committedRows.size());
        ArrayList<ShellTranscriptRow> rows =
                new ArrayList<>(committedRows.subList(committedSkipped, committedRows.size()));
        visibleTemporary = visibleTemporary.subList(skipped - committedSkipped, visibleTemporary.size());
        visibleTemporary.stream()
                .filter(item -> messages.stream().noneMatch(item::belongsTo))
                .map(item -> temporaryRow(item).atSequence(temporarySequence(item, messages)))
                .forEach(rows::add);
        for (OutgoingMessage message : messages) {
            rows.add(new ShellTranscriptRow(
                    message.id(),
                    TranscriptPresenter.presentOutgoing(message),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(message),
                    message.afterSequence()));
            visibleTemporary.stream()
                    .filter(item -> item.belongsTo(message))
                    .map(item -> temporaryRow(item).atSequence(message.afterSequence()))
                    .forEach(rows::add);
        }
        if (!messages.isEmpty()) {
            // 稳定排序使同一锚点的失败消息维持提交顺序，晚到历史不能把旧失败挪到新回复后面。
            rows.sort(Comparator.comparingLong(ShellTranscriptRow::sequence));
        }
        return rows;
    }

    private List<ChatSurface.TemporaryMessage> nativeTemporary() {
        return temporary.stream()
                .filter(item -> (!item.text().isEmpty() || item.activity() == ChatSurface.Activity.WAITING)
                        && !committedIds.contains(item.id()))
                .toList();
    }

    private long temporarySequence(ChatSurface.TemporaryMessage item, List<OutgoingMessage> messages) {
        long fallback =
                messages.isEmpty() ? Long.MAX_VALUE : messages.getFirst().afterSequence();
        return java.util.stream.LongStream.concat(
                        transcript.items().stream()
                                .filter(value -> item.turnId()
                                        .filter(value.turnId()::equals)
                                        .isPresent())
                                .mapToLong(ItemEnvelope::sequence),
                        transcript.history().stream()
                                .filter(value -> item.turnId()
                                        .filter(value.turnId()::equals)
                                        .isPresent())
                                .mapToLong(ItemHistoryEntry::sequence))
                .max()
                .orElse(fallback);
    }

    private void rebuildCommittedRows() {
        // 暂态正文变化不重新解码已提交内容；缓存只保留上次原生展示的有界历史，内容替换时整体失效。
        nativeItems = transcript.items();
        nativeHistory = transcript.history();
        ArrayList<ShellTranscriptRow> rows = new ArrayList<>();
        committedIds.clear();
        nativeFormatter.replaceItems(nativeItems);
        nativeItems.forEach(item -> {
            committedIds.add(item.id().toString());
            var presented = nativeFormatter.present(item);
            rows.add(new ShellTranscriptRow(
                    item.id().toString(),
                    presented,
                    Optional.empty(),
                    fullMessage(item, presented),
                    Optional.empty(),
                    item.sequence()));
        });
        nativeHistory.forEach(item -> {
            if (committedIds.add(item.id().toString())) {
                var presented = TranscriptPresenter.presentHistory(item);
                rows.add(new ShellTranscriptRow(
                        item.id().toString(),
                        presented,
                        Optional.of(item),
                        item.truncated() ? item.bodyReference() : Optional.empty(),
                        Optional.empty(),
                        item.sequence()));
            }
        });
        committedRows = List.copyOf(rows);
        // 导航先发布空历史，缺失 Item 不代表撤销展开；按会话及最多 500 个显式展开身份有界保存。
    }

    private void clearNative() {
        nativeItems = List.of();
        nativeHistory = List.of();
        committedRows = List.of();
        committedIds.clear();
        nativeFormatter.replaceItems(List.of());
        summary.getItems().clear();
        visible(earlier, false);
        visible(latest, false);
    }

    private static ShellTranscriptRow temporaryRow(ChatSurface.TemporaryMessage item) {
        String text = item.text();
        boolean waiting = item.activity() == ChatSurface.Activity.WAITING;
        boolean streaming = item.activity() == ChatSurface.Activity.STREAMING;
        boolean tail = item.textOffsetUtf16() > 0 || text.length() > 32_768;
        int start = Math.max(0, text.length() - 32_768);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        String title = waiting ? "助手 · 等待回复…" : streaming ? "助手 · 正在回复…" : "助手";
        if (item.incomplete()) {
            title += " · 未完成";
        }
        String body = waiting ? "模型正在准备回复…" : text.substring(start);
        return new ShellTranscriptRow(
                item.id(),
                new PresentedItem(title, (tail ? "[正文较长；显示末尾，完成后可打开全文]\n" : "") + body, "message-assistant"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Long.MAX_VALUE);
    }

    private void preview(DocumentReference reference) {
        sidePanels.openDocument();
        selectDocument(true);
        documents.open(reference);
    }

    private void selectDocument(boolean selected) {
        visible(documents, selected);
        visible(pending, !selected);
        sidePanels.selectDocument(selected);
    }

    private void external(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) {
            JavaClawDesktop.openExternal(uri);
        }
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    @Override
    public void close() {
        nativeTranscript.visibleProperty().removeListener(nativeVisibility);
        invalidations.close();
        chat.close();
        documents.close();
        expanded.clear();
    }
}
