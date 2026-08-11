package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProbeCommand;
import com.javaclaw.application.onboarding.OnboardingApplicationService.Provider;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetupCommand;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.onboarding.OnboardingViewModel.StatusTone;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 首次向导 Controller：协调步骤事件、应用用例和 FXML 卡片生命周期。 */
public final class OnboardingController implements AutoCloseable {

    private static final int TOTAL_STEPS = 3;

    @FXML private Label stepIndicator;
    @FXML private Hyperlink skipButton;
    @FXML private VBox stepOnePane;
    @FXML private VBox stepTwoPane;
    @FXML private VBox stepThreePane;
    @FXML private GridPane providerGrid;
    @FXML private Label providerTitleLabel;
    @FXML private Label providerHintLabel;
    @FXML private TextField baseUrlField;
    @FXML private TextField modelNameField;
    @FXML private PasswordField apiKeyField;
    @FXML private Button testConnectionButton;
    @FXML private Label testConnectionStatus;
    @FXML private Label summaryLabel;
    @FXML private Button previousButton;
    @FXML private Button nextButton;

    private final OnboardingApplicationService useCases;
    private final ProviderCardFactory cards;
    private final UiAsyncAction<ProviderSetup> saveAction;
    private final UiAsyncAction<OnboardingApplicationService.ProbeResult> probeAction;
    private final UiAsyncAction<Void> completeAction;
    private final OnboardingViewModel viewModel = new OnboardingViewModel();
    private final List<ProviderCardView> cardViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ChangeListener<StatusTone> statusStyleListener =
            (observable, oldValue, tone) -> applyStatusStyle(tone);
    private Runnable closeWindow = () -> {};
    private BooleanBinding busy;

    public OnboardingController(
            OnboardingApplicationService useCases,
            ProviderCardFactory cards,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cards = Objects.requireNonNull(cards, "cards");
        saveAction = new UiAsyncAction<>(tasks, fx);
        probeAction = new UiAsyncAction<>(tasks, fx);
        completeAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        viewModel.savingProperty().bind(saveAction.busyProperty());
        viewModel.probingProperty().bind(probeAction.busyProperty());
        viewModel.completingProperty().bind(completeAction.busyProperty());
        busy = viewModel.savingProperty().or(viewModel.probingProperty())
                .or(viewModel.completingProperty());

        stepIndicator.textProperty().bind(Bindings.format(
                "· 步骤 %d / " + TOTAL_STEPS, viewModel.currentStepProperty()));
        bindStep(stepOnePane, 1);
        bindStep(stepTwoPane, 2);
        bindStep(stepThreePane, 3);
        previousButton.disableProperty().bind(
                viewModel.currentStepProperty().isEqualTo(1).or(busy));
        nextButton.textProperty().bind(Bindings.when(
                        viewModel.currentStepProperty().isEqualTo(TOTAL_STEPS))
                .then("开始使用").otherwise("下一步"));
        nextButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> busy.get() || (viewModel.currentStepProperty().get() == 1
                        && viewModel.selectedProviderProperty().get() == null),
                busy, viewModel.currentStepProperty(), viewModel.selectedProviderProperty()));
        skipButton.disableProperty().bind(busy);
        testConnectionButton.disableProperty().bind(busy);

        testConnectionStatus.textProperty().bind(viewModel.statusProperty());
        testConnectionStatus.visibleProperty().bind(viewModel.statusProperty().isNotEmpty());
        testConnectionStatus.managedProperty().bind(testConnectionStatus.visibleProperty());
        viewModel.statusToneProperty().addListener(statusStyleListener);
        applyStatusStyle(StatusTone.INFO);
        loadProviderCards();
    }

    void configure(Runnable closeAction) {
        closeWindow = Objects.requireNonNull(closeAction, "closeAction");
    }

    @FXML
    private void skipRequested() {
        completeAndClose("onboarding-skip");
    }

    @FXML
    private void previousRequested() {
        int target = viewModel.currentStepProperty().get() - 1;
        if (target < 1) return;
        if (target == 2) prepareProviderForm();
        viewModel.currentStepProperty().set(target);
        viewModel.showStatus("", StatusTone.INFO);
    }

    @FXML
    private void nextRequested() {
        switch (viewModel.currentStepProperty().get()) {
            case 1 -> {
                prepareProviderForm();
                viewModel.currentStepProperty().set(2);
            }
            case 2 -> saveProvider();
            case 3 -> completeAndClose("onboarding-complete");
            default -> throw new IllegalStateException("未知向导步骤");
        }
    }

    @FXML
    private void testConnectionRequested() {
        Provider selected = viewModel.selectedProviderProperty().get();
        ProbeCommand command = new ProbeCommand(
                selected == null ? null : selected.id(), baseUrlField.getText());
        viewModel.showStatus("测试中...", StatusTone.INFO);
        probeAction.execute(
                TaskSpec.io("onboarding-connection-probe"),
                context -> useCases.probe(command),
                result -> viewModel.showStatus(
                        "连接成功（HTTP " + result.statusCode() + "）", StatusTone.SUCCESS),
                failure -> showFailure(failure));
    }

    private void loadProviderCards() {
        int index = 0;
        for (Provider provider : useCases.providers()) {
            ProviderCardView card = cards.create(provider, false, this::selectProvider);
            cardViews.add(card);
            providerGrid.add(card.root(), index % 3, index / 3);
            index++;
        }
    }

    private void selectProvider(Provider provider) {
        viewModel.selectedProviderProperty().set(provider);
        for (ProviderCardView card : cardViews) {
            card.controller().setSelected(card.controller().providerId().equals(provider.id()));
        }
    }

    private void prepareProviderForm() {
        Provider provider = viewModel.selectedProviderProperty().get();
        if (provider == null) return;
        providerTitleLabel.setText("配置 " + provider.displayName());
        providerHintLabel.setText(provider.local()
                ? "本地 Ollama 无需 API Key，确认 baseUrl 指向本机服务即可。"
                : "请填写 API Key；模型名与 baseUrl 已预填默认值，可按需修改。");
        baseUrlField.setText(provider.baseUrl());
        modelNameField.setText(provider.defaultModel());
        apiKeyField.clear();
        apiKeyField.setPromptText(provider.local() ? "无需填写" : "粘贴你的 API Key");
        apiKeyField.setDisable(provider.local());
    }

    private void saveProvider() {
        Provider selected = viewModel.selectedProviderProperty().get();
        ProviderSetupCommand command = new ProviderSetupCommand(
                selected == null ? null : selected.id(),
                baseUrlField.getText(), modelNameField.getText(), apiKeyField.getText());
        viewModel.showStatus("", StatusTone.INFO);
        saveAction.execute(
                TaskSpec.io("onboarding-provider-save"),
                context -> useCases.save(command),
                setup -> {
                    viewModel.savedSetupProperty().set(setup);
                    apiKeyField.clear();
                    summaryLabel.setText("提供商：" + setup.provider().displayName()
                            + "\n模型：" + setup.modelName()
                            + "\nBase URL：" + setup.baseUrl());
                    viewModel.currentStepProperty().set(3);
                },
                this::showFailure);
    }

    private void completeAndClose(String taskName) {
        completeAction.execute(
                TaskSpec.io(taskName),
                context -> {
                    useCases.complete();
                    return null;
                },
                ignored -> closeWindow.run(),
                this::showFailure);
    }

    private void showFailure(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank() ? "操作失败，请稍后重试" : failure.getMessage();
        viewModel.showStatus(message, StatusTone.ERROR);
    }

    private void bindStep(VBox pane, int step) {
        pane.visibleProperty().bind(viewModel.currentStepProperty().isEqualTo(step));
        pane.managedProperty().bind(pane.visibleProperty());
    }

    private void applyStatusStyle(StatusTone tone) {
        if (testConnectionStatus == null) return;
        testConnectionStatus.getStyleClass().removeAll(
                "onboarding-status-info", "onboarding-status-success",
                "onboarding-status-error");
        String styleClass = switch (tone == null ? StatusTone.INFO : tone) {
            case INFO -> "onboarding-status-info";
            case SUCCESS -> "onboarding-status-success";
            case ERROR -> "onboarding-status-error";
        };
        testConnectionStatus.getStyleClass().add(styleClass);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        saveAction.close();
        probeAction.close();
        completeAction.close();
        RuntimeException failure = closeCards();
        viewModel.statusToneProperty().removeListener(statusStyleListener);
        if (apiKeyField != null) apiKeyField.clear();
        viewModel.savingProperty().unbind();
        viewModel.probingProperty().unbind();
        viewModel.completingProperty().unbind();
        if (failure != null) throw failure;
    }

    private RuntimeException closeCards() {
        RuntimeException failure = null;
        for (int index = cardViews.size() - 1; index >= 0; index--) {
            try {
                cardViews.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cardViews.clear();
        if (providerGrid != null) providerGrid.getChildren().clear();
        return failure;
    }

    boolean isClosed() { return closed.get(); }
}
