package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.OptionalLong;

/** 从工作区 Spring Context 加载 Token 预算 FXML 对话框。 */
public final class SddBudgetDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            SddBudgetDialogFactory.class.getResource("/fxml/task/sdd-budget-dialog.fxml"),
            "缺少 sdd-budget-dialog.fxml");
    private final SpringFxmlLoader loader;

    public SddBudgetDialogFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public OptionalLong show(Window owner, Task task) {
        try (ViewHandle<VBox> handle = loader.load(VIEW)) {
            SddBudgetDialogController controller =
                    handle.controller(SddBudgetDialogController.class);
            Stage stage = new Stage();
            if (owner != null) stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("修改 Token 预算");
            Scene scene = new Scene(handle.root(), 440, 250);
            addStyle(scene, "/css/chat.css");
            addStyle(scene, "/css/sdd-task.css");
            stage.setScene(scene);
            controller.configure(task, stage::close);
            stage.showAndWait();
            return controller.result();
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 Token 预算对话框失败", failure);
        }
    }

    private static void addStyle(Scene scene, String path) {
        URL css = SddBudgetDialogFactory.class.getResource(path);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
    }
}
