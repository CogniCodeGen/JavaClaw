package com.javaclaw.desktop.shell;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
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
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.desktop.view.ChatSurface;
import com.javaclaw.desktop.view.TranscriptPresenter;
import com.javaclaw.protocol.CanonicalJson;

/** 主壳的表现层资源 owner；保留原生待处理内容、输入和审批，只替换正文表面。 */
final class ShellWebSurfaces implements AutoCloseable {
    private final ChatSurface chat;
    private final DocumentPreviewPane documents;
    private final ShellSidePanels sidePanels;
    private final VBox pending = new VBox();
    private final VBox nativeTranscript = new VBox();
    private final ListView<SummaryRow> summary = new ListView<>();
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
    private List<SummaryRow> committedRows = List.of();
    private final HashSet<String> committedIds = new HashSet<>();
    private boolean nativeFollowing = true;

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
        summary.setCellFactory(ignored -> new SummaryCell());
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
        Optional<WorkspaceId> next = state.threads().selectedWorkspace().map(Workspace::id);
        if (!workspace.equals(next) || !connection.equals(state.connection().connectedAt())) {
            documents.clear();
            workspace = next;
            connection = state.connection().connectedAt();
        }
        if (next.isPresent()) {
            temporary = transcript.stream().stream()
                    .flatMap(stream -> stream.messages().stream()
                            .filter(message -> message.state() != TurnStreamKind.COMMITTED
                                    || message.itemSequence()
                                            .filter(sequence -> sequence
                                                    > state.transcript().nextSequence())
                                            .isPresent())
                            .map(message -> new ChatSurface.TemporaryMessage(
                                    message.call().messageItemId().toString(),
                                    message.text(),
                                    message.state() == TurnStreamKind.CLOSED,
                                    message.textOffsetUtf16(),
                                    Optional.of(stream.turnId()))))
                    .toList();
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
                    state.transcript().outgoing());
        } else {
            temporary = List.of();
            clearNative();
            chat.clear();
        }
    }

    private void renderNative(boolean revealed) {
        if (workspace.isEmpty()) {
            clearNative();
            return;
        }
        boolean itemsChanged = !nativeItems.equals(transcript.items());
        List<SummaryRow> rows = nativeRows();
        boolean projected = !transcript.history().isEmpty()
                || !temporary.isEmpty()
                || transcript.outgoing().isPresent();
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

    private List<SummaryRow> nativeRows() {
        if (!nativeItems.equals(transcript.items()) || !nativeHistory.equals(transcript.history())) {
            rebuildCommittedRows();
        }
        List<ChatSurface.TemporaryMessage> visibleTemporary = temporary.stream()
                .filter(item -> !item.text().isEmpty() && !committedIds.contains(item.id()))
                .toList();
        int capacity = transcript.outgoing().isPresent() ? 499 : 500;
        int skipped = Math.max(0, committedRows.size() + visibleTemporary.size() - capacity);
        int committedSkipped = Math.min(skipped, committedRows.size());
        ArrayList<SummaryRow> rows = new ArrayList<>(committedRows.subList(committedSkipped, committedRows.size()));
        visibleTemporary = visibleTemporary.subList(skipped - committedSkipped, visibleTemporary.size());
        if (transcript.outgoing().isPresent()) {
            var message = transcript.outgoing().orElseThrow();
            // 上轮未提交尾文保留在新用户消息之前，只有相同 Turn 的分片才能成为它的回复。
            visibleTemporary.stream()
                    .filter(item -> !item.belongsTo(message))
                    .map(ShellWebSurfaces::temporaryRow)
                    .forEach(rows::add);
            var presented = TranscriptPresenter.presentOutgoing(message);
            rows.add(new SummaryRow(presented.title(), presented.body(), presented.styleClass(), Optional.empty()));
            visibleTemporary.stream()
                    .filter(item -> item.belongsTo(message))
                    .map(ShellWebSurfaces::temporaryRow)
                    .forEach(rows::add);
        } else {
            visibleTemporary.stream().map(ShellWebSurfaces::temporaryRow).forEach(rows::add);
        }
        return rows;
    }

    private void rebuildCommittedRows() {
        // 暂态正文变化不重新解码已提交内容；缓存只保留上次原生展示的有界历史，内容替换时整体失效。
        nativeItems = transcript.items();
        nativeHistory = transcript.history();
        ArrayList<SummaryRow> rows = new ArrayList<>();
        committedIds.clear();
        nativeFormatter.replaceItems(nativeItems);
        nativeItems.forEach(item -> {
            committedIds.add(item.id().toString());
            var presented = nativeFormatter.present(item);
            rows.add(new SummaryRow(presented.title(), presented.body(), presented.styleClass(), Optional.empty()));
        });
        nativeHistory.forEach(item -> {
            if (committedIds.add(item.id().toString())) {
                var presented = TranscriptPresenter.presentHistory(item);
                rows.add(
                        new SummaryRow(presented.title(), presented.body(), presented.styleClass(), Optional.of(item)));
            }
        });
        committedRows = List.copyOf(rows);
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

    private static SummaryRow temporaryRow(ChatSurface.TemporaryMessage item) {
        String text = item.text();
        boolean tail = item.textOffsetUtf16() > 0 || text.length() > 32_768;
        int start = Math.max(0, text.length() - 32_768);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return new SummaryRow(
                "ASSISTANT" + (item.incomplete() ? " · 未完成" : ""),
                (tail ? "[正文较长；显示末尾，完成后可打开全文]\n" : "") + text.substring(start),
                "message-assistant",
                Optional.empty());
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
    }

    private final class SummaryCell extends ListCell<SummaryRow> {
        private final Label title = new Label();
        private final Label body = new Label();
        private final Hyperlink open = documentLink("查看完整消息");
        private final VBox files = new VBox(4);
        private final VBox card = new VBox(6, title, body, open, files);

        private SummaryCell() {
            title.getStyleClass().add("message-role");
            body.setWrapText(true);
            open.setOnAction(event -> {
                if (getItem() != null) {
                    getItem()
                            .source()
                            .filter(ItemHistoryEntry::truncated)
                            .flatMap(ItemHistoryEntry::bodyReference)
                            .ifPresent(ShellWebSurfaces.this::preview);
                }
            });
        }

        private void addFile(String label, DocumentReference reference) {
            Hyperlink link = documentLink(label);
            link.setOnAction(event -> preview(reference));
            files.getChildren().add(link);
        }

        private static Hyperlink documentLink(String label) {
            Hyperlink link = new Hyperlink(label);
            link.getStyleClass().add("transcript-document-link");
            return link;
        }

        @Override
        protected void updateItem(SummaryRow row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            title.setText(row.title());
            body.setText(row.body().length() > 65_536 ? row.body().substring(0, 65_536) : row.body());
            card.getStyleClass().setAll(row.style());
            visible(
                    open,
                    row.source()
                            .filter(ItemHistoryEntry::truncated)
                            .flatMap(ItemHistoryEntry::bodyReference)
                            .isPresent());
            files.getChildren().clear();
            row.source().ifPresent(item -> {
                workspace.ifPresent(scope -> item.attachments()
                        .forEach(attachment -> addFile(
                                attachment.fileName(), DocumentReference.attachment(scope, item.id(), attachment))));
                item.fileReferences().forEach(reference -> addFile("查看引用文件", reference));
            });
            setGraphic(card);
        }
    }
    /** 原生展示行保留暂态与持久来源的区别；切简版只改变表现，不产生新的 Item。 */
    private record SummaryRow(String title, String body, String style, Optional<ItemHistoryEntry> source) {}
}
