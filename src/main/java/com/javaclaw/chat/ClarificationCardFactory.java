package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** 加载主动澄清 FXML；失败时立即销毁已创建 Controller。 */
public final class ClarificationCardFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            ClarificationCardFactory.class.getResource("/fxml/chat/clarification-card.fxml"),
            "缺少主动澄清 FXML");

    private final SpringFxmlLoader loader;

    public ClarificationCardFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    ClarificationCardView create(
            String agentName, String modelName, String timestamp,
            String reason, String question) {
        ViewHandle<HBox> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载主动澄清 FXML 失败", failure);
        }
        try {
            ClarificationCardController controller =
                    handle.controller(ClarificationCardController.class);
            controller.configure(agentName, modelName, timestamp, reason, question);
            ClarificationCardView view = new ClarificationCardView(handle);
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
