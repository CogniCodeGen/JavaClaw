package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 加载循环检测确认卡；创建和配置均受 FX 线程约束。 */
public final class LoopDecisionFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            LoopDecisionFactory.class.getResource("/fxml/chat/loop-decision.fxml"),
            "缺少循环确认 FXML");

    private final SpringFxmlLoader loader;

    public LoopDecisionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    LoopDecisionView create(String toolName, int repeats, Consumer<Boolean> decision) {
        ViewHandle<HBox> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载循环确认 FXML 失败", failure);
        }
        try {
            LoopDecisionController controller = handle.controller(LoopDecisionController.class);
            controller.configure(toolName, repeats, decision);
            LoopDecisionView view = new LoopDecisionView(handle);
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
