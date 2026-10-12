package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 从工作区 Spring Context 创建可释放的工作流中心窗口。 */
public final class WorkflowViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            WorkflowViewFactory.class.getResource("/fxml/workflow/workflow-view.fxml"),
            "缺少 workflow-view.fxml");
    private static final List<String> STYLES =
            List.of("/css/chat.css", "/css/controls.css", "/css/workflow-center.css");
    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public WorkflowViewFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    public WorkflowView create(Window owner, Consumer<WorkflowItem> onPublished) {
        ViewHandle<StackPane> handle = load();
        try {
            WorkflowViewController controller = handle.controller(WorkflowViewController.class);
            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("JavaClaw · 工作流中心");
            stage.setMinWidth(1080);
            stage.setMinHeight(700);
            Scene scene = new Scene(handle.root(), 1320, 820);
            addStyles(scene);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE),
                    controller::cancelConnection);
            stage.setScene(scene);
            return new WorkflowView(stage, handle, controller, fx, onPublished);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<StackPane> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载工作流中心失败", failure);
        }
    }

    private static void addStyles(Scene scene) {
        for (String path : STYLES) {
            URL css = WorkflowViewFactory.class.getResource(path);
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        }
    }
}
