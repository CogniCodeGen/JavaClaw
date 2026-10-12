package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** 创建工具结果或规划发言 FXML 控件；两种变体共享结构与生命周期协议。 */
public final class ExpandableMarkdownBlockFactory {

    enum Variant {
        SUB_AGENT,
        PLAN_AGENT
    }

    private static final URL TEMPLATE = Objects.requireNonNull(
            ExpandableMarkdownBlockFactory.class.getResource(
                    "/fxml/chat/expandable-markdown-block.fxml"),
            "缺少可折叠 Markdown 结果 FXML");

    private final SpringFxmlLoader loader;

    public ExpandableMarkdownBlockFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    ExpandableMarkdownBlockView create(
            Variant variant, String title, boolean contentInitiallyAvailable) {
        ViewHandle<VBox> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载可折叠 Markdown 结果 FXML 失败", failure);
        }
        try {
            ExpandableMarkdownBlockController controller =
                    handle.controller(ExpandableMarkdownBlockController.class);
            controller.configure(variant, title, contentInitiallyAvailable);
            ExpandableMarkdownBlockView view =
                    new ExpandableMarkdownBlockView(handle, controller);
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
