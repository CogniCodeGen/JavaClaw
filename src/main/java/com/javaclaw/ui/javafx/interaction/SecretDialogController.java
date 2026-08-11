package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.SecretRequest;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/** 安全输入弹窗 Controller；关闭时会清空控件和 ViewModel 中的明文。 */
public final class SecretDialogController implements AutoCloseable {

    @FXML private Label messageLabel;
    @FXML private PasswordField secretField;

    private final SecretDialogViewModel viewModel = new SecretDialogViewModel();
    private final ChangeListener<String> lengthLimiter =
            (observable, previous, current) -> viewModel.clamp();

    @FXML
    private void initialize() {
        messageLabel.textProperty().bind(viewModel.messageProperty());
        secretField.textProperty().bindBidirectional(viewModel.secretProperty());
        secretField.textProperty().addListener(lengthLimiter);
    }

    void configure(SecretRequest request) {
        viewModel.apply(java.util.Objects.requireNonNull(request, "request"));
    }

    char[] takeSecret() {
        return viewModel.takeAndClear();
    }

    @Override
    public void close() {
        secretField.textProperty().removeListener(lengthLimiter);
        secretField.textProperty().unbindBidirectional(viewModel.secretProperty());
        messageLabel.textProperty().unbind();
        secretField.clear();
        viewModel.clear();
    }
}
