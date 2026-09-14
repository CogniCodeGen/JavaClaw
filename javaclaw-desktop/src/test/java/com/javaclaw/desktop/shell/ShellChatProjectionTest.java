package com.javaclaw.desktop.shell;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollToEvent;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.facade.TurnStreamSnapshot;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.state.ThreadState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellChatProjectionTest {
    @Test
    void 原生已展开详情在导航空快照后重新加载历史仍保持展开() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.web().useFallback();
                var tool = new ItemHistoryEntry(
                        ItemId.random(),
                        fixture.turn,
                        2,
                        "tool-result",
                        Optional.empty(),
                        "工具结果 · 成功\n调用：detail-one\n{\"tools\":[]}",
                        Optional.empty(),
                        false,
                        Instant.EPOCH,
                        List.of(),
                        List.of());
                var transcript = new TranscriptState(List.of(), 2, false, Optional.empty(), false, List.of(tool));
                fixture.show(transcript);
                fixture.host.applyCss();
                fixture.host.layout();
                ((Hyperlink) fixture.summary().lookup(".tool-details-toggle")).fire();
                assertTrue(((TextArea) fixture.summary().lookup(".tool-details-body")).isVisible());
                fixture.show(TranscriptState.empty());
                fixture.show(transcript);
                fixture.host.applyCss();
                fixture.host.layout();
                assertTrue(((TextArea) fixture.summary().lookup(".tool-details-body")).isVisible());
                assertEquals("收起详情", ((Hyperlink) fixture.summary().lookup(".tool-details-toggle")).getText());
            }
        });
    }

    @Test
    void 五百条未提交尾文的原生窗口仍保留最新用户回显且不超过预算() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.web().useFallback();
                List<TurnStreamSnapshot.Message> messages = IntStream.range(0, 500)
                        .mapToObj(index -> new TurnStreamSnapshot.Message(
                                new TurnStreamCall(index + 1, "previous", ItemId.random()),
                                "旧回合尾文 " + index,
                                TurnStreamKind.CLOSED,
                                Optional.empty()))
                        .toList();
                TurnStreamSnapshot stream =
                        new TurnStreamSnapshot(fixture.turn, "500", messages, Optional.empty(), Optional.empty());
                var outgoing = new OutgoingMessage(
                        "local:latest", "本地发送必须留在窗口中", Optional.empty(), OutgoingMessage.Status.SENDING);
                fixture.show(new TranscriptState(
                        List.of(), 1, true, Optional.of(stream), true, fixture.history, Optional.of(outgoing)));
                ListView<?> summary = fixture.summary();
                assertEquals(500, summary.getItems().size());
                assertTrue(summary.getItems().getLast().toString().contains(outgoing.text()));
                assertTrue(summary.getItems().get(498).toString().contains("旧回合尾文 499"));
                fixture.assertText(outgoing.text());
                fixture.assertText("你 · 正在发送…");
            }
        });
    }

    @Test
    void 排队运行显示等待动画而交互等待停止动画且原生降级提供静态提示() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> fixture.showActivity(TurnStatus.QUEUED, Optional.empty()));
            fixture.awaitActivity("waiting");
            FxTestSupport.run(() -> {
                assertEquals("模型正在准备回复", fixture.script("document.querySelector('.model-activity-label').textContent"));
                fixture.web().useFallback();
                fixture.assertText("助手 · 等待回复…");
                fixture.assertText("模型正在准备回复…");
                fixture.showActivity(TurnStatus.WAITING, Optional.empty());
                fixture.assertMissingText("等待回复");
                fixture.showActivity(TurnStatus.RUNNING, Optional.empty());
                fixture.assertText("助手 · 等待回复…");
                fixture.showActivity(TurnStatus.FAILED, Optional.empty());
                fixture.assertMissingText("等待回复");
                fixture.showActivity(TurnStatus.COMPLETED, Optional.empty());
                fixture.assertMissingText("等待回复");
                fixture.showActivity(TurnStatus.CANCELLED, Optional.empty());
                fixture.assertMissingText("等待回复");
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 审批或输入待处理时运行Turn不显示模型等待动画() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.web().useFallback();
                fixture.showActivity(TurnStatus.RUNNING, Optional.empty(), fixture.approvalInteraction());
                fixture.assertMissingText("等待回复");
                fixture.showActivity(TurnStatus.RUNNING, Optional.empty(), fixture.inputInteraction());
                fixture.assertMissingText("等待回复");
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 首个流事件替换等待占位且交互等待中的既有正文不继续动画() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> fixture.showActivity(TurnStatus.RUNNING, Optional.empty()));
            fixture.awaitActivity("waiting");
            FxTestSupport.run(() ->
                    fixture.showActivity(TurnStatus.RUNNING, Optional.of(fixture.stream("", TurnStreamKind.STARTED))));
            fixture.awaitRows(2);
            fixture.awaitActivity("waiting");
            FxTestSupport.run(() -> fixture.showActivity(
                    TurnStatus.RUNNING, Optional.of(fixture.stream("公开正文", TurnStreamKind.TEXT_DELTA))));
            fixture.awaitActivity("streaming");
            FxTestSupport.run(() -> fixture.showActivity(
                    TurnStatus.COMPLETED, Optional.of(fixture.stream("公开正文", TurnStreamKind.TEXT_DELTA))));
            fixture.awaitActivity("settled");
            FxTestSupport.run(() -> fixture.showActivity(
                    TurnStatus.RUNNING, Optional.of(fixture.stream("公开正文", TurnStreamKind.TEXT_DELTA))));
            fixture.awaitActivity("streaming");
            FxTestSupport.run(() -> fixture.showActivity(
                    TurnStatus.WAITING, Optional.of(fixture.stream("公开正文", TurnStreamKind.TEXT_DELTA))));
            fixture.awaitActivity("settled");
            FxTestSupport.run(() -> {
                assertEquals(0, fixture.number("document.querySelectorAll('.model-activity').length"));
                assertEquals(2, fixture.number("document.querySelectorAll('article').length"));
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 健康Web不更新隐藏列表且切换简版立即显示最新分片并复用历史投影() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> fixture.showStream("首个分片", TurnStreamKind.TEXT_DELTA));
            fixture.awaitText("首个分片");
            FxTestSupport.run(() -> {
                ListView<?> summary = fixture.summary();
                AtomicInteger changes = new AtomicInteger();
                summary.getItems().addListener((ListChangeListener<Object>) ignored -> changes.incrementAndGet());
                assertTrue(summary.getItems().isEmpty());
                for (int index = 0; index < 20; index++) {
                    fixture.showStream("后续分片 " + index, TurnStreamKind.TEXT_DELTA);
                }
                assertEquals(0, changes.get());
                fixture.web().useFallback();
                fixture.assertText("后续分片 19");
                assertEquals(1, changes.get());
                Object history = summary.getItems().getFirst();
                fixture.showStream("最终分片", TurnStreamKind.CLOSED);
                fixture.assertText("最终分片");
                fixture.assertText("未完成");
                assertSame(history, summary.getItems().getFirst());
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void Web恢复后停止备用投影且再次自动降级同步采用最新状态() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.showStream("简版旧正文", TurnStreamKind.TEXT_DELTA);
                fixture.web().useFallback();
                fixture.web().retry();
            });
            fixture.awaitText("简版旧正文");
            FxTestSupport.run(() -> {
                fixture.showStream("恢复后的正文", TurnStreamKind.TEXT_DELTA);
                assertTrue(fixture.summary().getItems().getLast().toString().contains("简版旧正文"));
                fixture.failWeb();
            });
            fixture.awaitText("恢复后的正文");
            FxTestSupport.run(() -> {
                fixture.showStream("自动降级前最后分片", TurnStreamKind.CLOSED);
                fixture.failWeb();
                fixture.assertText("自动降级前最后分片");
                fixture.assertText("未完成");
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 原生持久列表只在内容变化或恢复跟随时定位而重复刷新保持阅读位置() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.web().useFallback();
                AtomicInteger scrolls = new AtomicInteger();
                fixture.legacy.addEventFilter(ScrollToEvent.scrollToTopIndex(), ignored -> scrolls.incrementAndGet());
                ItemEnvelope first = message(1);
                List<ItemEnvelope> initial = List.of(first);
                fixture.showLegacy(initial, true);
                assertEquals(1, scrolls.get());
                fixture.showLegacy(initial, true);
                fixture.showLegacy(initial, false);
                List<ItemEnvelope> appended = List.of(first, message(2));
                fixture.showLegacy(appended, false);
                assertEquals(1, scrolls.get());
                fixture.showLegacy(appended, true);
                assertEquals(2, scrolls.get());
                fixture.showLegacy(appended, true);
                assertEquals(2, scrolls.get());
            }
        });
    }

    private static ItemEnvelope message(long sequence) {
        return new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                sequence,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                new CanonicalJson()
                        .encode(new CorePayloads.Message(
                                MessageRole.ASSISTANT, "持久正文 " + sequence, List.of(), Optional.empty())),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
    }

    private static final class Fixture implements AutoCloseable {
        private final DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new IllegalStateException("测试不连接服务端");
                },
                Runnable::run,
                Clock.systemUTC());
        private final Workspace workspace = new Workspace(
                WorkspaceId.random(),
                "备用列表",
                Path.of("/private/tmp"),
                WorkspaceLifecycle.ACTIVE,
                1,
                Instant.EPOCH,
                Instant.EPOCH);
        private final ConversationThread thread = DesktopTestFixtures.thread(workspace);
        private final TurnId turn =
                DesktopTestFixtures.turn(thread, TurnStatus.RUNNING, 1).id();
        private final TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());
        private final List<ItemHistoryEntry> history = history();
        private final ListView<ItemEnvelope> legacy = new ListView<>();
        private final StackPane host = new StackPane();
        private final VBox progress = new VBox();
        private final ShellSidePanels panels =
                new ShellSidePanels(new BorderPane(), new VBox(), progress, new Button());
        private final ShellWebSurfaces surfaces = new ShellWebSurfaces(presenter, progress, host, legacy, panels);
        private final Stage stage = new Stage();

        private Fixture() {
            Scene scene = new Scene(host, 760, 500);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
        }

        private List<ItemHistoryEntry> history() {
            ItemId item = ItemId.random();
            return List.of(new ItemHistoryEntry(
                    item,
                    turn,
                    1,
                    "message",
                    Optional.of(MessageRole.USER),
                    "已提交历史",
                    Optional.of(DocumentReference.message(workspace.id(), item, "body")),
                    false,
                    Instant.EPOCH,
                    List.of(),
                    List.of()));
        }

        private void showStream(String text, TurnStreamKind kind) {
            show(new TranscriptState(List.of(), 1, true, Optional.of(stream(text, kind)), true, history));
        }

        private TurnStreamSnapshot stream(String text, TurnStreamKind kind) {
            return new TurnStreamSnapshot(
                    turn,
                    "cursor",
                    List.of(new TurnStreamSnapshot.Message(call, text, kind, Optional.empty())),
                    Optional.empty(),
                    Optional.empty());
        }

        private void showActivity(TurnStatus status, Optional<TurnStreamSnapshot> stream) {
            showActivity(status, stream, InteractionState.initial());
        }

        private void showActivity(
                TurnStatus status, Optional<TurnStreamSnapshot> stream, InteractionState interaction) {
            AgentTurn active = DesktopTestFixtures.turn(thread, status, status.ordinal() + 1L);
            show(new TranscriptState(List.of(), 1, true, stream, true, history), Optional.of(active), interaction);
        }

        private InteractionState approvalInteraction() {
            return new InteractionState(
                    List.of(),
                    Optional.empty(),
                    List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)),
                    InputInteractionState.initial(),
                    false,
                    Optional.empty());
        }

        private InteractionState inputInteraction() {
            return new InteractionState(
                    List.of(),
                    Optional.empty(),
                    List.of(),
                    new InputInteractionState(
                            List.of(DesktopTestFixtures.input()), Optional.empty(), Optional.empty(), 1),
                    false,
                    Optional.empty());
        }

        private void showLegacy(List<ItemEnvelope> items, boolean following) {
            // 主壳先更新持久列表，再把相同快照交给正文表面；此处保留这个调用顺序。
            if (!legacy.getItems().equals(items)) {
                legacy.getItems().setAll(items);
            }
            show(new TranscriptState(items, items.getLast().sequence(), following));
        }

        private void show(TranscriptState transcript) {
            show(transcript, Optional.empty(), InteractionState.initial());
        }

        private void show(TranscriptState transcript, Optional<AgentTurn> activeTurn) {
            show(transcript, activeTurn, InteractionState.initial());
        }

        private void show(TranscriptState transcript, Optional<AgentTurn> activeTurn, InteractionState interaction) {
            DesktopState initial = DesktopState.initial();
            surfaces.render(new DesktopState(
                    initial.connection(),
                    initial.navigation(),
                    new ThreadState(
                            List.of(workspace),
                            Optional.of(workspace),
                            List.of(thread),
                            Optional.of(thread),
                            activeTurn),
                    transcript,
                    interaction));
        }

        private WebSurfaceHost web() {
            return (WebSurfaceHost) host.getChildren().getFirst();
        }

        private ListView<?> summary() {
            return (ListView<?>) host.lookup("#transcriptSummary");
        }

        private Object script(String source) {
            return web().getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine()
                    .executeScript(source);
        }

        private void awaitText(String text) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> web().acknowledged()
                    && Boolean.TRUE.equals(script("document.body.innerText.includes('" + text + "')"))));
        }

        private void awaitActivity(String activity) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> web().acknowledged()
                    && activity.equals(
                            script("document.querySelector('article:last-of-type')" + "?.dataset.activity || ''"))));
        }

        private void awaitRows(int count) {
            FxTestSupport.await(() -> FxTestSupport.call(
                    () -> web().acknowledged() && number("document.querySelectorAll('article').length") == count));
        }

        private double number(String source) {
            return ((Number) script(source)).doubleValue();
        }

        private void failWeb() {
            script("window.JavaClawSurface.post('error','测试页面故障')");
        }

        private void assertText(String text) {
            host.applyCss();
            host.layout();
            assertTrue(host.lookupAll(".label").stream()
                    .map(Label.class::cast)
                    .anyMatch(label -> label.isVisible() && label.getText().contains(text)));
        }

        private void assertMissingText(String text) {
            host.applyCss();
            host.layout();
            assertTrue(host.lookupAll(".label").stream()
                    .map(Label.class::cast)
                    .noneMatch(label -> label.isVisible() && label.getText().contains(text)));
        }

        @Override
        public void close() {
            surfaces.close();
            panels.close();
            stage.close();
            try {
                presenter.close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
