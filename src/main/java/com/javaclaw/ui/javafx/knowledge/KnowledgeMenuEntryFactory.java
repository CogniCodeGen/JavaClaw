package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 从固定 FXML 模板创建知识库菜单项。 */
public final class KnowledgeMenuEntryFactory {

    private static final URL CHECK = resource("knowledge-check-item.fxml");
    private static final URL ACTION = resource("knowledge-action-item.fxml");
    private static final URL HEADER = resource("knowledge-header-item.fxml");
    private static final URL SEPARATOR = resource("knowledge-separator.fxml");

    private final SpringFxmlLoader loader;

    public KnowledgeMenuEntryFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    KnowledgeMenuEntryView check(
            String text, boolean selected, String styleClass,
            Consumer<Boolean> selection) {
        ViewHandle<CheckMenuItem> handle = load(CHECK, "勾选项");
        try {
            if (styleClass != null && !styleClass.isBlank()) {
                handle.root().getStyleClass().add(styleClass);
            }
            handle.controller(KnowledgeCheckItemController.class)
                    .configure(text, selected, selection);
            return new KnowledgeMenuEntryView(handle.root(), handle);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(handle, failure);
            throw failure;
        }
    }

    KnowledgeMenuEntryView action(
            String text, boolean disabled, String styleClass, Runnable action) {
        ViewHandle<MenuItem> handle = load(ACTION, "动作项");
        try {
            handle.controller(KnowledgeActionItemController.class)
                    .configure(text, disabled, styleClass, action);
            return new KnowledgeMenuEntryView(handle.root(), handle);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(handle, failure);
            throw failure;
        }
    }

    KnowledgeMenuEntryView header(String title) {
        ViewHandle<CustomMenuItem> handle = load(HEADER, "分组标题");
        try {
            handle.controller(KnowledgeHeaderItemController.class).configure(title);
            return new KnowledgeMenuEntryView(handle.root(), handle);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(handle, failure);
            throw failure;
        }
    }

    KnowledgeMenuEntryView separator() {
        ViewHandle<SeparatorMenuItem> handle = load(SEPARATOR, "分隔符");
        return new KnowledgeMenuEntryView(handle.root(), handle);
    }

    private <T extends MenuItem> ViewHandle<T> load(URL template, String kind) {
        try {
            return loader.load(template);
        } catch (IOException failure) {
            throw new IllegalStateException("加载知识库菜单" + kind + " FXML 失败", failure);
        }
    }

    private static void closeAfterFailure(ViewHandle<?> handle, Throwable failure) {
        try {
            handle.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static URL resource(String name) {
        return Objects.requireNonNull(KnowledgeMenuEntryFactory.class.getResource(
                "/fxml/chat/" + name), "缺少知识库菜单 FXML: " + name);
    }
}
