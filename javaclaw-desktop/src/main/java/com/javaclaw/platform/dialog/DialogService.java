package com.javaclaw.platform.dialog;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;

/**
 * 页面 Controller 使用的统一对话与通知门面。
 *
 * <p>线程切换、等待超时和无界面降级由底层端口负责。同步确认可能阻塞，调用方必须从托管
 * I/O 任务调用，不能阻塞 JavaFX Application Thread；通知与图片预览为非阻塞语义。</p>
 */
public final class DialogService {

    private final UserInteractionPort interaction;

    public DialogService(UserInteractionPort interaction) {
        this.interaction = java.util.Objects.requireNonNull(interaction, "interaction");
    }

    public ConfirmDecision confirm(ConfirmRequest request) {
        return interaction.confirmEx(request);
    }

    public String choose(ChoiceRequest request) {
        return interaction.choose(request);
    }

    public char[] requestSecret(SecretRequest request) {
        return interaction.requestSecret(request);
    }

    public void notify(ToastRequest request) {
        interaction.notify(request);
    }

    public void previewImage(java.nio.file.Path image) {
        interaction.previewImage(image);
    }
}
