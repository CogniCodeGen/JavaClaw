package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 为用户、助手、系统和欢迎消息加载同一份语义化 FXML。 */
public final class ChatMessageRowFactory {

    enum Variant {
        USER,
        ASSISTANT,
        SYSTEM,
        WELCOME
    }

    private static final URL TEMPLATE = Objects.requireNonNull(
            ChatMessageRowFactory.class.getResource("/fxml/chat/chat-message-row.fxml"),
            "缺少静态聊天消息 FXML");

    private final SpringFxmlLoader loader;

    public ChatMessageRowFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    ChatMessageRowView create(
            Variant variant,
            ChatMessage message,
            String agentName,
            String modelName,
            String metadata,
            List<? extends Node> extraImages,
            BiConsumer<ImageView, File> imageZoom) {
        ViewHandle<StackPane> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载静态聊天消息 FXML 失败", failure);
        }
        try {
            ChatMessageRowController controller =
                    handle.controller(ChatMessageRowController.class);
            controller.configure(variant, message, agentName, modelName, metadata,
                    extraImages, imageZoom);
            ChatMessageRowView view = new ChatMessageRowView(handle, controller);
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
