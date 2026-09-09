package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.MemoryManagementCenterFixture;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class DesktopSendEchoTest {
    private static final String MESSAGE = "发送回显必须先于服务端回执出现";

    @Test
    void 真实聊天在发送RPC仍阻塞时显示用户消息且权威历史到达后仅保留一条() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.open();
            fixture.send();
            fixture.awaitSending();
            fixture.awaitWeb(MESSAGE, "你 · 正在发送…");
            FxTestSupport.run(() -> {
                assertEquals(1, fixture.matchingArticles());
                assertSame(fixture.composer(), fixture.scene().getFocusOwner());
                assertEquals(MESSAGE, fixture.composer().getText());
                assertEquals(0, fixture.server.turnStarts.get());
                assertEquals(1, fixture.release.getCount());
                // 用户等待时可以继续操作；迟到回执不能把焦点拉回输入框或正文。
                fixture.button("settingsButton").requestFocus();
            });
            fixture.accept();
            fixture.awaitCommitted();
            FxTestSupport.run(() -> {
                assertEquals(1, fixture.matchingArticles());
                assertEquals("", fixture.composer().getText());
                assertSame(fixture.button("settingsButton"), fixture.scene().getFocusOwner());
                assertEquals(1, fixture.server.turnStarts.get());
            });
        }
    }

    @Test
    void 原生简版在发送RPC阻塞时同样显示本地消息而且回执后不重复() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.open();
            FxTestSupport.run(() -> fixture.host().useFallback());
            fixture.send();
            fixture.awaitSending();
            fixture.awaitNative(MESSAGE, "你 · 正在发送…");
            FxTestSupport.run(() -> {
                assertEquals(3, fixture.summary().getItems().size());
                assertSame(fixture.composer(), fixture.scene().getFocusOwner());
                assertEquals(0, fixture.server.turnStarts.get());
            });
            fixture.accept();
            fixture.awaitCommittedState();
            fixture.awaitNative(MESSAGE, "你");
            FxTestSupport.run(() -> {
                assertEquals(3, fixture.summary().getItems().size());
                assertEquals(1, fixture.visibleNativeLabels(MESSAGE));
                assertEquals("", fixture.composer().getText());
            });
        }
    }

    @Test
    void 发送失败保留原草稿及未确认回显而且不抢输入焦点() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.open();
            fixture.send();
            fixture.awaitSending();
            fixture.awaitWeb(MESSAGE, "你 · 正在发送…");
            fixture.release.countDown();
            FxTestSupport.await(() -> fixture.state
                            .get()
                            .transcript()
                            .outgoing()
                            .filter(value -> value.status() == OutgoingMessage.Status.UNCONFIRMED)
                            .isPresent()
                    && !fixture.state.get().interaction().busy());
            fixture.awaitWeb(MESSAGE, "你 · 发送未确认");
            FxTestSupport.run(() -> {
                assertEquals(MESSAGE, fixture.composer().getText());
                assertSame(fixture.composer(), fixture.scene().getFocusOwner());
                assertEquals(1, fixture.matchingArticles());
                assertEquals(0, fixture.server.turnStarts.get());
                assertFalse(fixture.button("sendButton").isDisabled());
            });
        }
    }

    /** 延迟仅发生在内存 RPC 后台线程；FX Scene 必须在解除闩锁之前完成真实消息布局。 */
    private static final class Fixture implements AutoCloseable {
        private final CanonicalJson json = new CanonicalJson();
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<DesktopState> state = new AtomicReference<>(DesktopState.initial());
        private final List<ItemHistoryEntry> initial = List.of(
                history(1, MessageRole.USER, "历史问题", TurnId.random()),
                history(2, MessageRole.ASSISTANT, "历史答复", TurnId.random()));
        private final DesktopPresenter presenter;
        private final boolean fail;
        private Map<String, Object> controls;
        private DesktopShellController controller;
        private ManagementCenterWindow center;
        private Stage stage;

        private Fixture(boolean fail) throws IOException {
            this.fail = fail;
            server.streamEnabled = true;
            server.completion = TurnStatus.COMPLETED;
            server.history = () -> new ItemHistoryResult(initial, 2, false);
            server.requestOverride = this::override;
            var client = server.client(ignored -> {});
            presenter = new DesktopPresenter(ignored -> client, Platform::runLater, Clock.systemUTC());
            presenter.subscribe(state::set);
        }

        private Optional<Object> override(JsonRpcRequest request) {
            return switch (request.method()) {
                case "turn/start" -> delayedStart();
                case "approval/list" -> Optional.of(new CoreRpcContracts.ApprovalListResult(List.of()));
                case "turn/input/list" -> Optional.of(new InputJobRpcContracts.InputListResult(List.of()));
                case "extension/query" -> codingOutput(request);
                default -> Optional.empty();
            };
        }

        private Optional<Object> delayedStart() {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试未在消息可见后释放发送 RPC");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("发送测试已结束", interrupted);
            }
            if (fail) {
                throw new IllegalStateException("模拟发送回执失败");
            }
            return Optional.empty();
        }

        private Optional<Object> codingOutput(JsonRpcRequest request) {
            var query = json.decode(request.params(), ExtensionRpcContracts.CallPayload.class);
            if (query.extensionId().equals(CodingContracts.EXTENSION_ID)
                    && query.operation().equals("execution/list")) {
                return Optional.of(new ExtensionRpcContracts.CallResult(
                        json.encode(new CodingResults.ExecutionList(List.of())), 0));
            }
            return Optional.empty();
        }

        private void open() {
            FxTestSupport.run(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                    Parent root = loader.load();
                    controls = new HashMap<>(loader.getNamespace());
                    controller = loader.getController();
                    Scene scene = new Scene(root, 1280, 820);
                    DesktopStylesheets.apply(scene);
                    center = MemoryManagementCenterFixture.create(presenter, scene);
                    stage = new Stage();
                    stage.setTitle("JavaClaw 发送回显回归 · 内存 RPC");
                    stage.setScene(scene);
                    stage.show();
                    controller.attach(presenter, center);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> configuration().ready()
                    && host().acknowledged()
                    && !state.get().interaction().busy()));
        }

        private void send() {
            FxTestSupport.run(() -> {
                composer().setText(MESSAGE);
                composer().requestFocus();
                assertFalse(button("sendButton").isDisabled());
                button("sendButton").fire();
            });
        }

        private void awaitSending() {
            FxTestSupport.await(() -> entered.getCount() == 0
                    && state.get()
                            .transcript()
                            .outgoing()
                            .filter(value -> value.status() == OutgoingMessage.Status.SENDING)
                            .isPresent());
        }

        private void accept() {
            List<ItemHistoryEntry> committed = List.of(
                    initial.get(0),
                    initial.get(1),
                    history(
                            3,
                            MessageRole.USER,
                            MESSAGE,
                            DesktopTestFixtures.turn().id()));
            server.history = () -> new ItemHistoryResult(committed, 3, false);
            release.countDown();
        }

        private void awaitCommittedState() {
            FxTestSupport.await(() -> state.get().transcript().outgoing().isEmpty()
                    && state.get().transcript().history().size() == 3);
        }

        private void awaitCommitted() {
            awaitCommittedState();
            awaitWeb(MESSAGE, "你");
        }

        private void awaitWeb(String message, String title) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> host().acknowledged()
                    && Boolean.TRUE.equals(web().getEngine()
                            .executeScript("[...document.querySelectorAll('article')].some(node => {"
                                    + "const r=node.getBoundingClientRect(),s=getComputedStyle(node);"
                                    + "return node.textContent.includes("
                                    + javascriptText(message)
                                    + ") && node.textContent.includes("
                                    + javascriptText(title)
                                    + ") && r.width>0 && r.height>0 && r.top>=0 && r.bottom<=innerHeight"
                                    + " && s.display!=='none' && s.visibility==='visible';})"))));
        }

        private int matchingArticles() {
            return ((Number) web().getEngine()
                            .executeScript(
                                    "[...document.querySelectorAll('article')].filter(node=>node.textContent.includes("
                                            + javascriptText(MESSAGE) + ")).length"))
                    .intValue();
        }

        private String javascriptText(String value) {
            return "(" + json.encode(Map.of("value", value)).json() + ").value";
        }

        private void awaitNative(String message, String title) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> {
                scene().getRoot().applyCss();
                scene().getRoot().layout();
                return summary().isVisible()
                        && summary().isManaged()
                        && visibleNativeLabels(message) == 1
                        && visibleNativeLabels(title) >= 1;
            }));
        }

        private long visibleNativeLabels(String text) {
            return host().lookupAll(".label").stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .filter(label -> label.getText().equals(text) && visible(label))
                    .count();
        }

        private boolean visible(Node node) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (!current.isVisible()) {
                    return false;
                }
            }
            Bounds bounds = node.localToScene(node.getBoundsInLocal());
            Bounds viewport = summary().localToScene(summary().getBoundsInLocal());
            return bounds.getWidth() > 0 && bounds.getHeight() > 0 && viewport.contains(bounds);
        }

        private Scene scene() {
            return stage.getScene();
        }

        private Button button(String id) {
            return (Button) controls.get(id);
        }

        private TextArea composer() {
            return (TextArea) controls.get("composer");
        }

        private ChatConfigurationPanel configuration() {
            return (ChatConfigurationPanel)
                    ((VBox) controls.get("executionHost")).getChildren().getFirst();
        }

        private WebSurfaceHost host() {
            return (WebSurfaceHost)
                    ((StackPane) controls.get("transcriptHost")).getChildren().getFirst();
        }

        private WebView web() {
            return host().getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        private ListView<?> summary() {
            return (ListView<?>) host().lookup("#transcriptSummary");
        }

        private static ItemHistoryEntry history(long sequence, MessageRole role, String text, TurnId turn) {
            return new ItemHistoryEntry(
                    ItemId.random(),
                    turn,
                    sequence,
                    "message",
                    Optional.of(role),
                    text,
                    Optional.empty(),
                    false,
                    DesktopTestFixtures.NOW,
                    List.of(),
                    List.of());
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            FxTestSupport.run(() -> {
                if (controller != null) {
                    controller.close();
                    center.dispose();
                    stage.close();
                }
            });
            presenter.close();
            server.close();
        }
    }
}
