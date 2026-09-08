package com.javaclaw.desktop.acceptance.chatdocument;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ChatSurface;
import com.javaclaw.protocol.CanonicalJson;

final class ChatAcceptanceFixture implements AutoCloseable {
    final WorkspaceId workspace = WorkspaceId.random();
    final List<DocumentReference> previews = new ArrayList<>();
    final ChatSurface chat;
    final Stage stage;

    ChatAcceptanceFixture() {
        chat = FxTestSupport.call(
                () -> new ChatSurface(new Label("简版"), previews::add, ignored -> {}, () -> {}, ignored -> {}));
        stage = FxTestSupport.call(() -> window(new Scene(chat.node(), 760, 500)));
    }

    void show(String identity, List<ItemEnvelope> items, List<ChatSurface.TemporaryMessage> stream) {
        chat.show(identity, workspace, items, List.of(), stream, false);
    }

    void await(String text) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> chat.node().acknowledged()
                && engine().executeScript("document.getElementById('surface').textContent")
                        .toString()
                        .contains(text)));
    }

    void awaitCount(int count) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> chat.node().acknowledged()
                && engine().executeScript("document.getElementById('surface').dataset.messageCacheSize")
                        .equals(Integer.toString(count))));
    }

    String script(String source) {
        return FxTestSupport.call(() -> String.valueOf(engine().executeScript(source)));
    }

    WebEngine engine() {
        return chat.node().getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow()
                .getEngine();
    }

    static Stage nativeTranscript(List<ItemEnvelope> items) {
        try {
            // 直接复用当前 FXML 的原生 TranscriptCell；不绑定 Presenter，不建立旧 Runtime 或读取数据。
            FXMLLoader loader = new FXMLLoader(ChatAcceptanceFixture.class.getResource("/fxml/main.fxml"));
            loader.load();
            @SuppressWarnings("unchecked")
            ListView<ItemEnvelope> list =
                    (ListView<ItemEnvelope>) loader.getNamespace().get("transcriptList");
            ((StackPane) list.getParent()).getChildren().remove(list);
            list.getItems().setAll(items);
            StackPane root = new StackPane(list);
            root.getStyleClass().add("chat-root");
            return window(new Scene(root, 760, 500));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static Map<String, Object> nativeStyle(Stage window) {
        return FxTestSupport.call(() -> {
            Region bubble = (Region) window.getScene().getRoot().lookup(".message-user");
            Label role = (Label) bubble.lookup(".message-role");
            return Map.of(
                    "background",
                    bubble.getBackground().getFills().getFirst().getFill().toString(),
                    "padding",
                    bubble.getPadding().toString(),
                    "roleFont",
                    role.getFont().toString(),
                    "roleColor",
                    role.getTextFill().toString(),
                    "bubbleWidth",
                    bubble.getWidth());
        });
    }

    private static Stage window(Scene scene) {
        DesktopStylesheets.apply(scene);
        Stage window = new Stage();
        window.setScene(scene);
        window.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return window;
    }

    static ItemEnvelope message(
            ItemId id, long sequence, MessageRole role, String text, List<AttachmentRef> attachments) {
        return new ItemEnvelope(
                id,
                TurnId.random(),
                sequence,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                new CanonicalJson().encode(new CorePayloads.Message(role, text, attachments, Optional.empty())),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
    }

    @Override
    public void close() {
        FxTestSupport.run(() -> {
            chat.close();
            stage.close();
        });
    }
}
