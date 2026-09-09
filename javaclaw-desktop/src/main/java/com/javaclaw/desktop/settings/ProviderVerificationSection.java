package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;

import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

/** Provider 本地检查结果与显式计费验证控件。 */
final class ProviderVerificationSection {
    private final BiConsumer<Boolean, String> chatVerifier;
    private final BiConsumer<Boolean, String> embeddingVerifier;
    private final FormSection content;
    private final Label probeStatus = hint();
    private final ComboBox<String> chatModel = new ComboBox<>();
    private final ComboBox<String> embeddingModel = new ComboBox<>();
    private final Label chatStatus = hint();
    private final Label embeddingStatus = hint();
    private final Button verifyChat;
    private final Button verifyEmbedding;
    private boolean renderingModels;
    private boolean confirming;

    ProviderVerificationSection(
            PlatformComponentFactory components,
            BiConsumer<Boolean, String> chatVerifier,
            BiConsumer<Boolean, String> embeddingVerifier,
            Runnable bindingChanged) {
        this.chatVerifier = chatVerifier;
        this.embeddingVerifier = embeddingVerifier;
        verifyChat = components.action("发送测试对话", ActionStyle.DANGER, ActionSize.NORMAL);
        verifyEmbedding = components.action("发送测试向量请求", ActionStyle.DANGER, ActionSize.NORMAL);
        verifyChat.setId("providerVerifyChatButton");
        verifyEmbedding.setId("providerVerifyEmbeddingButton");
        chatStatus.setId("providerChatVerificationStatus");
        embeddingStatus.setId("providerEmbeddingVerificationStatus");
        verifyChat.setOnAction(event -> confirmRoundTrip(ProviderModelPurpose.CHAT));
        verifyEmbedding.setOnAction(event -> confirmRoundTrip(ProviderModelPurpose.EMBEDDING));
        chatModel.valueProperty().addListener((ignored, previous, value) -> selectionChanged(bindingChanged));
        embeddingModel.valueProperty().addListener((ignored, previous, value) -> selectionChanged(bindingChanged));
        content = new FormSection("运行状态", "本地检查只验证配置。测试对话和测试向量都会真实调用所选模型，可能产生少量费用。");
        content.addField("最近检查", probeStatus);
        content.addField("测试对话模型", chatModel);
        content.addField("对话测试结果", chatStatus);
        content.addFullWidth(verifyChat);
        content.addField("测试向量模型", embeddingModel);
        content.addField("向量测试结果", embeddingStatus);
        content.addFullWidth(verifyEmbedding);
    }

    Node content() {
        return content;
    }

    /** 确认交互计入页面等待状态，但不能作为本次验证自身的配置阻塞条件。 */
    boolean confirming() {
        return confirming;
    }

    Optional<String> modelId(ProviderModelPurpose purpose) {
        return Optional.ofNullable(model(purpose).getValue());
    }

    void renderModels(List<ProviderModelSpec> models) {
        renderingModels = true;
        try {
            // 目录替换的中间空选择不是用户意图，页面在完整渲染后统一绑定精确模型。
            renderModels(chatModel, models, ProviderModelPurpose.CHAT);
            renderModels(embeddingModel, models, ProviderModelPurpose.EMBEDDING);
        } finally {
            renderingModels = false;
        }
    }

    private void selectionChanged(Runnable bindingChanged) {
        if (!renderingModels) {
            bindingChanged.run();
        }
    }

    void renderProbe(Optional<ProviderStatus> status) {
        probeStatus.setText(
                status.map(value -> SettingsLabels.providerReadiness(value.readiness()) + " · " + value.checkedAt())
                        .orElse("尚未检查"));
    }

    void renderVerification(ProviderVerificationSettingsState state) {
        Button action = action(state.purpose());
        ComboBox<String> selector = model(state.purpose());
        Label status = status(state.purpose());
        action.setDisable(state.pending() || !state.available());
        selector.setDisable(state.pending());
        status.setText(
                state.pending() || state.phase() == SettingsLoadState.ERROR || !state.available()
                        ? state.message()
                        : state.result()
                                .map(result -> SettingsLabels.providerVerificationState(result.state())
                                        + " · 耗时 "
                                        + result.latencyMillis()
                                        + " 毫秒"
                                        + usage(result))
                                .orElse(state.message()));
    }

    private void confirmRoundTrip(ProviderModelPurpose purpose) {
        if (confirming) {
            return;
        }
        String consequence = purpose == ProviderModelPurpose.CHAT ? "此操作会发送一次真实测试对话，可能产生费用" : "此操作会发送一次真实测试向量请求，可能产生费用";
        String actionLabel = purpose == ProviderModelPurpose.CHAT ? "验证对话模型" : "验证向量模型";
        TextInputDialog dialog = PlatformDialogs.exactText(
                content,
                "确认可能计费的模型验证",
                consequence,
                ProviderVerificationRpcContracts.BILLING_CONFIRMATION,
                actionLabel);
        // 持续保护至确认回调提交完成；关闭弹窗恢复焦点时，页面刷新不能抢先使模型绑定不可用。
        confirming = true;
        try {
            dialog.showAndWait().ifPresent(value -> verifier(purpose).accept(true, value));
        } finally {
            confirming = false;
        }
    }

    private BiConsumer<Boolean, String> verifier(ProviderModelPurpose purpose) {
        return purpose == ProviderModelPurpose.CHAT ? chatVerifier : embeddingVerifier;
    }

    private ComboBox<String> model(ProviderModelPurpose purpose) {
        return purpose == ProviderModelPurpose.CHAT ? chatModel : embeddingModel;
    }

    private Button action(ProviderModelPurpose purpose) {
        return purpose == ProviderModelPurpose.CHAT ? verifyChat : verifyEmbedding;
    }

    private Label status(ProviderModelPurpose purpose) {
        return purpose == ProviderModelPurpose.CHAT ? chatStatus : embeddingStatus;
    }

    private static void renderModels(
            ComboBox<String> selector, List<ProviderModelSpec> models, ProviderModelPurpose purpose) {
        String previous = selector.getValue();
        List<String> available = models.stream()
                .filter(candidate -> candidate.supports(purpose))
                .map(ProviderModelSpec::modelId)
                .toList();
        if (!selector.getItems().equals(available)) {
            selector.getItems().setAll(available);
        }
        selector.setValue(Optional.ofNullable(previous)
                .filter(available::contains)
                .orElseGet(() -> available.stream().findFirst().orElse(null)));
    }

    private static String usage(com.javaclaw.api.ProviderVerificationResult result) {
        if (result.purpose() == ProviderModelPurpose.EMBEDDING) {
            return result.state() == com.javaclaw.api.ProviderVerificationState.SUCCEEDED ? " · 已校验单向量及维度" : "";
        }
        return " · 输入/输出令牌 "
                + result.usage()
                        .map(value -> value.inputTokens() + "/" + value.outputTokens())
                        .orElse("未知");
    }

    private static Label hint() {
        Label label = new Label();
        label.setWrapText(true);
        label.getStyleClass().add("sec-hint");
        return label;
    }
}
