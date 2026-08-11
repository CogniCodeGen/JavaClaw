package com.javaclaw.ui.javafx.site;

import com.javaclaw.app.UIHelper;
import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import com.javaclaw.application.site.SiteCredentialApplicationService.SaveCommand;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** 创建 FXML 站点凭据编辑弹窗，并在弹窗结束后立即销毁 Controller。 */
public final class SiteCredentialEditorFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SiteCredentialEditorFactory.class.getResource("/fxml/site/site-credential-editor.fxml"),
            "缺少 site-credential-editor.fxml");
    private final SpringFxmlLoader loader;

    public SiteCredentialEditorFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** 必须在 FX 线程调用；取消返回空，表单关闭后不会保留明文密码节点。 */
    public Optional<SaveCommand> show(Window owner, Credential existing) {
        ViewHandle<VBox> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载站点凭据编辑器失败", failure);
        }
        Button save = null;
        try {
            SiteCredentialEditorController controller =
                    handle.controller(SiteCredentialEditorController.class);
            controller.configure(existing);
            Dialog<SaveCommand> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle(existing == null ? "添加站点" : "编辑站点");
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            dialog.getDialogPane().setPrefWidth(520);
            save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            save.disableProperty().bind(controller.validBinding().not());
            dialog.setResultConverter(button -> button == ButtonType.OK
                    ? controller.command() : null);
            UIHelper.styleDialog(dialog);
            return dialog.showAndWait();
        } finally {
            if (save != null) {
                save.disableProperty().unbind();
            }
            handle.close();
        }
    }
}
