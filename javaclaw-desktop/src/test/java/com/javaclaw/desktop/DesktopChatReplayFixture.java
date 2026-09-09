package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.MemoryManagementCenterFixture;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.ProviderRpcContracts;

/** 性能回放使用生产 FXML、Presenter 与 SDK；所有业务数据来自内存 RPC，禁止启动模型或写入用户偏好。 */
final class DesktopChatReplayFixture implements AutoCloseable {
    private final PresenterRpcServer server = new PresenterRpcServer();
    private final AtomicReference<DesktopState> state = new AtomicReference<>(DesktopState.initial());
    private final TurnStreamCall call = new TurnStreamCall(1, "performance", ItemId.random());
    private final DesktopPresenter presenter;
    private volatile List<ProviderEndpoint> providers = List.of();
    private Map<String, Object> controls;
    private DesktopShellController controller;
    private ManagementCenterWindow center;
    private Stage stage;
    private int cursor;
    private long offset;

    DesktopChatReplayFixture(int count, String body, boolean streaming) throws IOException {
        server.streamEnabled = true;
        server.completion = streaming ? TurnStatus.RUNNING : TurnStatus.COMPLETED;
        List<ItemHistoryEntry> history = IntStream.range(0, count)
                .mapToObj(index -> history(index, body))
                .toList();
        server.history = () -> new ItemHistoryResult(history, history.size(), false);
        server.requestOverride = request -> switch (request.method()) {
            case "approval/list" -> Optional.of(new CoreRpcContracts.ApprovalListResult(List.of()));
            case "turn/input/list" -> Optional.of(new InputJobRpcContracts.InputListResult(List.of()));
            case "provider/list" -> Optional.of(new ProviderRpcContracts.ProviderListResult(providers));
            case "extension/query" -> codingOutput(request);
            default -> Optional.empty();
        };
        var client = server.client(ignored -> {});
        presenter = new DesktopPresenter(ignored -> client, Platform::runLater, Clock.systemUTC());
        presenter.subscribe(state::set);
    }

    private static Optional<Object> codingOutput(JsonRpcRequest request) {
        CanonicalJson json = new CanonicalJson();
        var query = json.decode(request.params(), ExtensionRpcContracts.CallPayload.class);
        if (query.extensionId().equals(CodingContracts.EXTENSION_ID)
                && query.operation().equals("execution/list")) {
            // 空执行目录必须是合法 SDK 响应，避免无关的协议错误污染空闲采样和布局截图。
            return Optional.of(
                    new ExtensionRpcContracts.CallResult(json.encode(new CodingResults.ExecutionList(List.of())), 0));
        }
        return Optional.empty();
    }

    void open() {
        FxTestSupport.run(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                Parent root = loader.load();
                controls = new HashMap<>(loader.getNamespace());
                controller = loader.getController();
                Scene scene = new Scene(root, 1280, 820);
                DesktopStylesheets.apply(scene);
                center = MemoryManagementCenterFixture.create(presenter, scene);
                controller.attach(presenter, center);
                stage = new Stage();
                stage.setTitle("JavaClaw 主聊天性能回放 · 无模型");
                stage.setScene(scene);
                stage.show();
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
        FxTestSupport.await(() -> FxTestSupport.call(() -> configuration().ready() && host().acknowledged()));
        if (server.completion == TurnStatus.RUNNING) {
            FxTestSupport.await(() -> server.streamSubscription != null);
        }
    }

    Scene scene() {
        return stage.getScene();
    }

    void providers(List<ProviderEndpoint> catalog) {
        providers = List.copyOf(catalog);
    }

    Node control(String id) {
        Object value = controls.get(id);
        return value instanceof Node node ? node : scene().lookup("#" + id);
    }

    void resize(int width, int height) {
        FxTestSupport.run(() -> {
            stage.setWidth(width + stage.getWidth() - scene().getWidth());
            stage.setHeight(height + stage.getHeight() - scene().getHeight());
        });
        FxTestSupport.await(() -> FxTestSupport.call(
                () -> Math.abs(scene().getWidth() - width) < 1 && Math.abs(scene().getHeight() - height) < 1));
    }

    TextArea composer() {
        return (TextArea) controls.get("composer");
    }

    WebView web() {
        return host().getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow();
    }

    WebSurfaceHost host() {
        return (WebSurfaceHost)
                ((StackPane) controls.get("transcriptHost")).getChildren().getFirst();
    }

    void startStream() {
        emit(TurnStreamKind.STARTED, "");
    }

    void append(String text) {
        emit(TurnStreamKind.TEXT_DELTA, text);
    }

    private void emit(TurnStreamKind kind, String text) {
        String previous = cursor == 0 ? "START" : Integer.toString(cursor);
        server.emitStream(List.of(new TurnStreamEvent(
                DesktopTestFixtures.turn().id(),
                Integer.toString(++cursor),
                previous,
                new TurnStreamEvent.Data(kind, Optional.of(call), text, offset, Optional.empty(), Optional.empty()))));
        offset += text.length();
    }

    void awaitTail(String text) {
        FxTestSupport.await(() -> state.get().transcript().stream().stream()
                .flatMap(value -> value.messages().stream())
                .anyMatch(message -> message.text().endsWith(text)));
        FxTestSupport.await(() -> FxTestSupport.call(() -> host().acknowledged()
                && web().getEngine()
                        .executeScript("document.body.textContent")
                        .toString()
                        .contains(text)));
    }

    private ChatConfigurationPanel configuration() {
        return (ChatConfigurationPanel)
                ((VBox) controls.get("executionHost")).getChildren().getFirst();
    }

    private static ItemHistoryEntry history(int index, String body) {
        return new ItemHistoryEntry(
                ItemId.random(),
                DesktopTestFixtures.turn().id(),
                index + 1L,
                "message",
                Optional.of(index == 0 ? MessageRole.USER : MessageRole.ASSISTANT),
                "历史消息 " + index + "\n\n" + body,
                Optional.empty(),
                false,
                DesktopTestFixtures.NOW,
                List.of(),
                List.of());
    }

    @Override
    public void close() throws Exception {
        FxTestSupport.run(() -> {
            if (controller != null) {
                controller.close();
                center.dispose();
                stage.close();
            }
        });
        presenter.close();
        server.close();
        if (server.turnStarts.get() != 0) {
            throw new AssertionError("性能回放不得创建 Turn 或调用模型");
        }
    }
}
