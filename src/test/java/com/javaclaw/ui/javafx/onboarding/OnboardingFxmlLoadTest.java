package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.LocalInferenceQuickSetupApplicationService;
import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.testsupport.EmptyInferenceManagementService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class OnboardingFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private OnboardingView view;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (view != null) runFx(view::close);
        if (context != null) context.close();
    }

    @Test
    void loadsCardsValidatesSavesProbesCompletesAndDestroysControllers() throws Exception {
        FakeService service = createContext();
        OnboardingController controller = callFx(() -> {
            view = context.getBean(OnboardingViewFactory.class).create(null);
            view.stage().show();
            return view.controller();
        });

        assertEquals(720.0, callFx(() -> view.stage().getScene().getWidth()));
        assertEquals(520.0, callFx(() -> view.stage().getScene().getHeight()));
        assertEquals(5, callFx(() -> grid("providerGrid").getChildren().size()));
        assertTrue(callFx(() -> button("nextButton").isDisabled()));

        runFx(() -> card(0).getOnMouseClicked().handle(null));
        assertFalse(callFx(() -> button("nextButton").isDisabled()));
        runFx(() -> button("nextButton").fire());
        assertEquals("https://dashscope.example/v1",
                callFx(() -> text("baseUrlField").getText()));

        runFx(() -> button("testConnectionButton").fire());
        awaitFx(() -> label("testConnectionStatus").getText().equals(
                "连接成功（HTTP 204）"));

        runFx(() -> button("nextButton").fire());
        awaitFx(() -> label("testConnectionStatus").getText().contains(
                "云端模型需要填写 API Key"));

        runFx(() -> {
            password("apiKeyField").setText("secret");
            button("nextButton").fire();
        });
        awaitFx(() -> pane("stepThreePane").isVisible());
        assertEquals(1, service.saves.get());
        assertTrue(callFx(() -> label("summaryLabel").getText().contains("dash-model")));

        runFx(() -> button("nextButton").fire());
        awaitFx(() -> service.completions.get() == 1 && controller.isClosed());
        assertFalse(callFx(() -> view.stage().isShowing()));
    }

    private FakeService createContext() {
        context = new AnnotationConfigApplicationContext();
        FakeService service = new FakeService();
        context.registerBean(OnboardingApplicationService.class, () -> service);
        context.registerBean(InferenceManagementApplicationService.class,
                EmptyInferenceManagementService::new);
        context.registerBean(LocalInferenceQuickSetupApplicationService.class,
                EmptyQuickInference::new);
        context.registerBean(WorkspaceApplicationService.class, EmptyWorkspaceService::new);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ProviderCardFactory.class,
                () -> new ProviderCardFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(OnboardingViewFactory.class,
                () -> new OnboardingViewFactory(service,
                        context.getBean(SpringFxmlLoader.class)));
        context.refresh();
        return service;
    }

    private Node card(int index) { return grid("providerGrid").getChildren().get(index); }
    private GridPane grid(String id) { return (GridPane) view.stage().getScene().lookup("#" + id); }
    private VBox pane(String id) { return (VBox) view.stage().getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) view.stage().getScene().lookup("#" + id); }
    private Label label(String id) { return (Label) view.stage().getScene().lookup("#" + id); }
    private TextField text(String id) {
        return (TextField) view.stage().getScene().lookup("#" + id);
    }
    private PasswordField password(String id) {
        return (PasswordField) view.stage().getScene().lookup("#" + id);
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition), "等待 JavaFX 状态超时");
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { completed.countDown(); }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static final class FakeService implements OnboardingApplicationService {
        private final Provider dash = new Provider(
                "DashScope", "阿里通义千问", "中文场景推荐",
                "https://dashscope.example/v1", "dash-model", false, true);
        private final List<Provider> providers = List.of(
                dash,
                provider("OpenAI"),
                provider("Anthropic"),
                provider("Gemini"),
                new Provider("Ollama", "Ollama", "本地", "http://localhost:11434/v1",
                        "qwen", true, true));
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();

        @Override public boolean required() { return true; }
        @Override public List<Provider> providers() { return providers; }

        @Override
        public ProviderSetup save(ProviderSetupCommand command) {
            if (command.apiKey() == null || command.apiKey().isBlank()) {
                throw new ValidationException("云端模型需要填写 API Key");
            }
            saves.incrementAndGet();
            return new ProviderSetup(dash, command.baseUrl(), command.modelName());
        }

        @Override public ProbeResult probe(ProbeCommand command) { return new ProbeResult(204); }
        @Override public void complete() { completions.incrementAndGet(); }

        private static Provider provider(String id) {
            return new Provider(id, id, "描述", "https://example.com/v1",
                    "model", false, false);
        }
    }

    private static final class EmptyWorkspaceService implements WorkspaceApplicationService {
        @Override public List<WorkspaceSummary> list() { return List.of(); }
        @Override public String currentWorkspaceId() { return "test"; }
        @Override public WorkspaceSummary create(String name) {
            throw new UnsupportedOperationException("UI load test");
        }
        @Override public boolean delete(String workspaceId) { return false; }
    }

    private static final class EmptyQuickInference
            implements LocalInferenceQuickSetupApplicationService {
        @Override public QuickSnapshot quickSnapshot() {
            return new QuickSnapshot(List.of(), null, DEFAULT_ALIAS, "",
                    false, false, false, List.of());
        }
        @Override public com.javaclaw.inference.api.InferenceModelAsset importLocalModel(
                Path source, Consumer<InferenceAssetPreparationPort.Progress> progress,
                BooleanSupplier cancelled) {
            throw new UnsupportedOperationException("UI load test");
        }
        @Override public InferenceManagementApplicationService.ProfileDraft defaultDraft(UUID assetId) {
            throw new UnsupportedOperationException("UI load test");
        }
        @Override public LoadResult loadAndPublish(
                LoadCommand command, Consumer<Progress> progress, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException("UI load test");
        }
    }
}
