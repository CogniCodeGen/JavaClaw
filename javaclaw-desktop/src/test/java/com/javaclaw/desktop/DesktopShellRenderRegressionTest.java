package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollToEvent;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.MemoryManagementCenterFixture;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实主壳和本地 RPC 重放流消息，观察冗余 UI 操作，不用机器速度作为正确性阈值。 */
class DesktopShellRenderRegressionTest {
    private static final int UPDATES = 24;

    @Test
    void 二十赫兹正文更新不重置无关列表且最终消息和继续输入都可见() throws Exception {
        try (ShellHarness shell = new ShellHarness(100)) {
            shell.start();
            FxTestSupport.await(() -> shell.server.streamSubscription != null
                    && !shell.latest.get().interaction().pendingApprovals().isEmpty());
            shell.awaitText("历史消息 99");
            Counters counts = FxTestSupport.call(shell::watchLists);
            shell.emitTextWhileEditing();
            shell.awaitText("STREAM_UPDATE_23");
            FxTestSupport.run(() -> {
                System.out.printf(
                        "SHELL_RESPONSE stream inputEvents=%d textUpdates=%d workspaceChanges=%d "
                                + "threadChanges=%d approvalChanges=%d typingScroll=%d%n",
                        UPDATES,
                        counts.textUpdates().get(),
                        counts.workspaces().get(),
                        counts.threads().get(),
                        counts.approvals().get(),
                        counts.scrolls().get());
                assertEquals(0, counts.workspaces().get(), "正文更新不应替换工作区目录");
                assertEquals(0, counts.threads().get(), "正文更新不应替换会话目录");
                assertEquals(0, counts.approvals().get(), "相同审批不应反复触发列表更新");
                assertEquals("字".repeat(UPDATES), shell.composer().getText());
                assertEquals(
                        shell.server.thread(),
                        shell.threads().getSelectionModel().getSelectedItem());
                assertTrue(shell.latest.get().transcript().stream()
                        .orElseThrow()
                        .messages()
                        .getFirst()
                        .text()
                        .contains("STREAM_UPDATE_23"));
            });
            // 验证监听器能捕获真正的审批变化，避免零计数源于未接到实际列表。
            shell.approvals.set(List.of());
            FxTestSupport.await(() ->
                    FxTestSupport.call(() -> shell.approvalList().getItems().isEmpty()));
            assertTrue(counts.approvals().get() > 0);
            assertEquals(0, shell.server.turnStarts.get(), "回放只恢复已有 Turn，不请求任何模型");
        }
    }

    @Test
    void 在非空原生转录上逐字编辑不会发起滚动命令() throws Exception {
        try (ShellHarness shell = new ShellHarness(-1)) {
            shell.start();
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> !shell.legacy().getItems().isEmpty()));
            FxTestSupport.run(() -> {
                AtomicInteger scrolls = new AtomicInteger();
                shell.legacy().addEventFilter(ScrollToEvent.scrollToTopIndex(), event -> scrolls.incrementAndGet());
                shell.legacy().applyCss();
                shell.legacy().scrollTo(0);
                assertTrue(scrolls.get() > 0, "公开滚动事件必须能观测真实 scrollTo 命令");
                scrolls.set(0);
                for (int index = 0; index < UPDATES; index++) {
                    shell.composer().appendText("字");
                }
                System.out.printf("SHELL_RESPONSE typing inputEvents=%d typingScroll=%d%n", UPDATES, scrolls.get());
                assertEquals(0, scrolls.get(), "编辑草稿只应更新输入操作，不应滚动转录列表");
                assertEquals("字".repeat(UPDATES), shell.composer().getText());
            });
        }
    }

    @Test
    void 目录实际变化仍更新且会话切换保留各自草稿() throws Exception {
        try (ShellHarness shell = new ShellHarness(0)) {
            shell.start();
            Counters counts = FxTestSupport.call(shell::watchLists);
            FxTestSupport.run(() -> shell.composer().setText("原对话未发送草稿"));
            Workspace before = shell.server.workspace();
            Workspace renamed = new Workspace(
                    before.id(),
                    "重命名后的项目",
                    before.root(),
                    before.lifecycle(),
                    before.revision() + 1,
                    before.createdAt(),
                    before.updatedAt());
            shell.workspaces.set(List.of(renamed));
            FxTestSupport.run(shell.presenter::refreshWorkspaceCatalog);
            FxTestSupport.await(() ->
                    FxTestSupport.call(() -> renamed.equals(shell.workspaces().getValue())));
            FxTestSupport.run(() -> {
                assertTrue(counts.workspaces().get() > 0);
                assertEquals("原对话未发送草稿", shell.composer().getText());
                shell.presenter.createThread(shell.second.title());
            });
            shell.awaitSelected(shell.second);
            FxTestSupport.run(() -> {
                assertTrue(counts.threads().get() > 0);
                assertEquals(
                        List.of(shell.server.thread(), shell.second),
                        shell.threads().getItems());
                assertEquals(
                        shell.second.title(),
                        shell.control("threadTitle", Label.class).getText());
                assertEquals("", shell.composer().getText());
                shell.composer().setText("第二个对话草稿");
                shell.threads().getSelectionModel().select(shell.server.thread());
            });
            shell.awaitSelected(shell.server.thread());
            FxTestSupport.run(() -> {
                assertEquals("原对话未发送草稿", shell.composer().getText());
                shell.threads().getSelectionModel().select(shell.second);
            });
            shell.awaitSelected(shell.second);
            FxTestSupport.run(() -> assertEquals("第二个对话草稿", shell.composer().getText()));
        }
    }

    private static <T> AtomicInteger changes(ObservableList<T> items) {
        AtomicInteger changes = new AtomicInteger();
        items.addListener((ListChangeListener<T>) change -> changes.incrementAndGet());
        return changes;
    }

    private record Counters(
            AtomicInteger workspaces,
            AtomicInteger threads,
            AtomicInteger approvals,
            AtomicInteger textUpdates,
            AtomicInteger scrolls) {}

    private static final class ShellHarness implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final AtomicReference<DesktopState> latest = new AtomicReference<>(DesktopState.initial());
        private final AtomicReference<List<Workspace>> workspaces = new AtomicReference<>(List.of(server.workspace()));
        private final AtomicReference<List<ConversationThread>> catalog =
                new AtomicReference<>(List.of(server.thread()));
        private final AtomicReference<List<ApprovalRecord>> approvals =
                new AtomicReference<>(List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)));
        private final ConversationThread second = secondThread();
        private final DesktopPresenter presenter;
        private final boolean running;
        private DesktopShellController controller;
        private ManagementCenterWindow center;
        private Map<String, Object> controls;
        private Stage stage;

        private ShellHarness(int historyCount) throws IOException {
            running = historyCount > 0;
            server.streamEnabled = historyCount >= 0;
            server.completion = running ? TurnStatus.RUNNING : TurnStatus.COMPLETED;
            List<ItemHistoryEntry> history = IntStream.range(0, Math.max(0, historyCount))
                    .mapToObj(ShellHarness::history)
                    .toList();
            server.history = () -> new ItemHistoryResult(history, history.size(), false);
            server.requestOverride = this::response;
            JavaClawClient client = server.client(ignored -> {});
            presenter = new DesktopPresenter(ignored -> client, Platform::runLater, Clock.systemUTC());
            presenter.subscribe(latest::set);
        }

        private Optional<Object> response(JsonRpcRequest request) {
            return switch (request.method()) {
                case "workspace/list" -> Optional.of(new CoreRpcContracts.WorkspaceListResult(workspaces.get()));
                case "thread/list" -> Optional.of(new CoreRpcContracts.ThreadListResult(catalog.get()));
                case "approval/list" -> Optional.of(new CoreRpcContracts.ApprovalListResult(approvals.get()));
                case "thread/create" -> {
                    catalog.set(List.of(server.thread(), second));
                    yield Optional.of(second);
                }
                default -> Optional.empty();
            };
        }

        private void start() {
            FxTestSupport.run(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                    Parent root = loader.load();
                    controls = new HashMap<>(loader.getNamespace());
                    controller = loader.getController();
                    Scene scene = new Scene(root, 1_280, 820);
                    DesktopStylesheets.apply(scene);
                    center = MemoryManagementCenterFixture.create(presenter, scene);
                    controller.attach(presenter, center);
                    stage = new Stage();
                    stage.setScene(scene);
                    stage.show();
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
            try {
                FxTestSupport.await(() -> FxTestSupport.call(this::initialized));
            } catch (AssertionError failure) {
                throw new AssertionError(FxTestSupport.call(this::initializationDiagnostic), failure);
            }
        }

        private boolean initialized() {
            DesktopState state = latest.get();
            // busy 包含正在运行的 Turn 生命周期；流场景应等到恢复订阅，不能等运行结束才开始回放。
            boolean observed = running
                    ? state.threads()
                                    .activeTurn()
                                    .filter(turn -> turn.status() == TurnStatus.RUNNING
                                            && turn.threadId()
                                                    .equals(server.thread().id()))
                                    .isPresent()
                            && server.streamSubscription != null
                    : !state.interaction().busy();
            return state.connection().status() == ConnectionState.Status.CONNECTED
                    && observed
                    && configuration().ready();
        }

        private String initializationDiagnostic() {
            DesktopState state = latest.get();
            return "主壳初始化未完成：connection=" + state.connection().status()
                    + ", busy=" + state.interaction().busy()
                    + ", active="
                    + state.threads()
                            .activeTurn()
                            .map(turn -> turn.status().name())
                            .orElse("none")
                    + ", subscribed=" + (server.streamSubscription != null)
                    + ", history=" + state.transcript().history().size()
                    + ", configurationReady=" + configuration().ready()
                    + ", error=" + state.interaction().error().orElse("");
        }

        private void emitTextWhileEditing() throws InterruptedException {
            TurnStreamCall call = new TurnStreamCall(1, "replay", ItemId.random());
            server.emitStream(List.of(event(call, 1, "START", TurnStreamKind.STARTED, "", 0)));
            long offset = 0;
            long start = System.nanoTime();
            for (int index = 0; index < UPDATES; index++) {
                long wait = start + TimeUnit.MILLISECONDS.toNanos(index * 50L) - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                String delta = "STREAM_UPDATE_" + index + "\n";
                server.emitStream(List.of(
                        event(call, index + 2, Integer.toString(index + 1), TurnStreamKind.TEXT_DELTA, delta, offset)));
                offset += delta.length();
                FxTestSupport.run(() -> composer().appendText("字"));
            }
        }

        private void awaitText(String text) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> {
                WebSurfaceHost host = (WebSurfaceHost)
                        control("transcriptHost", StackPane.class).getChildren().getFirst();
                if (!host.acknowledged()) {
                    return false;
                }
                return host.getChildren().stream()
                        .filter(WebView.class::isInstance)
                        .map(WebView.class::cast)
                        .anyMatch(web -> web.getEngine()
                                .executeScript("document.body.textContent")
                                .toString()
                                .contains(text));
            }));
        }

        private void awaitSelected(ConversationThread thread) {
            FxTestSupport.await(() -> latest.get()
                            .threads()
                            .selectedThread()
                            .filter(thread::equals)
                            .isPresent()
                    && !latest.get().interaction().busy()
                    && FxTestSupport.call(() -> configuration().ready()));
            FxTestSupport.run(
                    () -> assertFalse(control("errorLabel", Label.class).isVisible()));
        }

        private Counters watchLists() {
            AtomicInteger textUpdates = new AtomicInteger();
            AtomicReference<String> previous = new AtomicReference<>("");
            presenter.subscribe(state -> state.transcript().stream().stream()
                    .flatMap(stream -> stream.messages().stream())
                    .findFirst()
                    .map(message -> message.text())
                    .filter(text -> !text.equals(previous.getAndSet(text)))
                    .ifPresent(text -> textUpdates.incrementAndGet()));
            AtomicInteger scrolls = new AtomicInteger();
            legacy().addEventFilter(ScrollToEvent.scrollToTopIndex(), event -> scrolls.incrementAndGet());
            return new Counters(
                    changes(workspaces().getItems()),
                    changes(threads().getItems()),
                    changes(approvalList().getItems()),
                    textUpdates,
                    scrolls);
        }

        private TextArea composer() {
            return control("composer", TextArea.class);
        }

        private ChatConfigurationPanel configuration() {
            return (ChatConfigurationPanel)
                    control("executionHost", VBox.class).getChildren().getFirst();
        }

        private ListView<?> legacy() {
            return control("transcriptList", ListView.class);
        }

        @SuppressWarnings("unchecked")
        private ListView<ConversationThread> threads() {
            return control("threadList", ListView.class);
        }

        private ListView<?> approvalList() {
            return control("approvalList", ListView.class);
        }

        @SuppressWarnings("unchecked")
        private ComboBox<Workspace> workspaces() {
            return control("workspaceBox", ComboBox.class);
        }

        private <T> T control(String name, Class<T> type) {
            return type.cast(controls.get(name));
        }

        private ConversationThread secondThread() {
            ConversationThread first = server.thread();
            return new ConversationThread(
                    ThreadId.random(),
                    first.workspaceId(),
                    Optional.empty(),
                    first.executionIntent(),
                    "第二个对话",
                    first.status(),
                    1,
                    first.createdAt(),
                    first.updatedAt());
        }

        private static ItemHistoryEntry history(int index) {
            return new ItemHistoryEntry(
                    ItemId.random(),
                    DesktopTestFixtures.turn().id(),
                    index + 1L,
                    "message",
                    Optional.of(MessageRole.ASSISTANT),
                    "历史消息 " + index,
                    Optional.empty(),
                    false,
                    DesktopTestFixtures.NOW,
                    List.of(),
                    List.of());
        }

        private static TurnStreamEvent event(
                TurnStreamCall call, int cursor, String previous, TurnStreamKind kind, String text, long offset) {
            return new TurnStreamEvent(
                    DesktopTestFixtures.turn().id(),
                    Integer.toString(cursor),
                    previous,
                    new TurnStreamEvent.Data(
                            kind, Optional.of(call), text, offset, Optional.empty(), Optional.empty()));
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.run(() -> {
                if (controller != null) {
                    controller.close();
                }
                if (center != null) {
                    center.dispose();
                }
                if (stage != null) {
                    stage.hide();
                }
            });
            presenter.close();
            server.close();
        }
    }
}
