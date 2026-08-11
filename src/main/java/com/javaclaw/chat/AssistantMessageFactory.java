package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** 在 FX 线程按消息加载一次助手消息 FXML，并把销毁责任交给返回的门面。 */
public final class AssistantMessageFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            AssistantMessageFactory.class.getResource("/fxml/chat/assistant-message.fxml"),
            "缺少助手消息 FXML");

    private final SpringFxmlLoader loader;

    public AssistantMessageFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    AssistantMessageView create(String agentName, String modelName, String timestamp) {
        ViewHandle<HBox> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载助手消息 FXML 失败", failure);
        }
        try {
            AssistantMessageController controller =
                    handle.controller(AssistantMessageController.class);
            controller.configure(agentName, modelName, timestamp);
            AssistantMessageView view = new AssistantMessageView(handle, controller);
            controller.attach(view);
            return view;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
