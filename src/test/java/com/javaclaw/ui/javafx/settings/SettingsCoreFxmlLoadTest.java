package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.application.settings.ModelSettingsPort;
import com.javaclaw.application.settings.ModelSettingsProbePort;
import com.javaclaw.application.settings.ModelSettingsUseCase;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SettingsCoreFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<SettingsSectionView<?>> views = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private FakeSettingsPort settings;

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
        runFx(() -> {
            for (int index = views.size() - 1; index >= 0; index--) views.get(index).close();
            views.clear();
        });
        if (context != null) context.close();
    }

    @Test
    void allSectionsLoadIncludesAndPopulateThePersistedSnapshot() throws Exception {
        prepareContext();
        ModelSettingsSectionFactory factory = context.getBean(ModelSettingsSectionFactory.class);
        var model = add(callFx(() -> factory.createModel(ignored -> { })));
        var tiers = add(callFx(() -> factory.createTiers(ignored -> { })));
        var embedding = add(callFx(() -> factory.createEmbedding(ignored -> { })));

        runFx(() -> {
            render(model);
            render(tiers);
            render(embedding);
        });

        assertEquals("chat-model", callFx(() -> text(model, "modelNameField").getText()));
        assertEquals("normal-model", callFx(() -> text(tiers, "normalModelNameField").getText()));
        assertEquals("embedding-model", callFx(() -> text(embedding, "modelNameField").getText()));
        PasswordField secret = callFx(() ->
                (PasswordField) model.root().lookup("#secretField"));
        assertNotNull(secret, "fx:include 应加载可复用密钥控件");
        assertEquals("model-key", callFx(secret::getText));

        runFx(model::close);
        views.remove(model);
        assertEquals("", callFx(secret::getText), "页面关闭必须清除嵌套控件中的密钥");
    }

    @Test
    void modelControllerSavesAsynchronouslyAndKeepsValidationFailuresOutOfStorage()
            throws Exception {
        prepareContext();
        AtomicInteger applied = new AtomicInteger();
        var model = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createModel(ignored -> applied.incrementAndGet())));
        runFx(() -> render(model));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        runFx(() -> {
            text(model, "modelNameField").setText("updated-model");
            model.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> settings.modelSaves == 1 && applied.get() == 1);
        assertEquals("updated-model", settings.snapshot.model().modelName());

        runFx(() -> {
            text(model, "baseUrlField").setText("file:///tmp/not-http");
            model.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> failure.get() != null);
        assertEquals(1, settings.modelSaves, "校验失败不得写入配置");
        assertEquals("updated-model", settings.snapshot.model().modelName());
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        settings = new FakeSettingsPort(snapshot());
        context.registerBean(ModelSettingsPort.class, () -> settings);
        context.registerBean(ModelSettingsProbePort.class, FakeProbePort::new);
        context.registerBean(ModelSettingsApplicationService.class, () -> new ModelSettingsUseCase(
                context.getBean(ModelSettingsPort.class),
                context.getBean(ModelSettingsProbePort.class)));
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ModelSettingsSectionFactory.class,
                () -> new ModelSettingsSectionFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private <T extends SettingsSectionView<?>> T add(T view) {
        views.add(view);
        return view;
    }

    private static void render(SettingsSectionView<?> view) {
        new Scene((javafx.scene.Parent) view.root(), 800, 620);
        view.root().applyCss();
        view.root().autosize();
    }

    private static TextField text(SettingsSectionView<?> view, String id) {
        return (TextField) view.root().lookup("#" + id);
    }

    private static Snapshot snapshot() {
        return new Snapshot(
                new ModelSettings("OpenAI", "https://api.example/v1", "chat-model",
                        "model-key", true, 4096, "HTTP_2", 10, 120, 30,
                        30, 15, 15, 5, 0.92, 4.0, 2),
                new TierSettings(
                        new Tier(true, "OpenAI", "", "normal-model", "normal-key", false),
                        new Tier(false, "", "", "", "", false)),
                new EmbeddingSettings(true, "OpenAI", "https://api.example/v1",
                        "embedding-key", "embedding-model", 1024, 5, 0.35),
                "fake-config.json");
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition::getAsBoolean), "等待 JavaFX 状态超时");
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
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

    private static final class FakeSettingsPort implements ModelSettingsPort {
        private volatile Snapshot snapshot;
        private volatile int modelSaves;

        private FakeSettingsPort(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot load() { return snapshot; }
        @Override public void saveModel(ModelSettings value) {
            modelSaves++;
            snapshot = new Snapshot(value, snapshot.tiers(), snapshot.embedding(),
                    snapshot.storageDescription());
        }
        @Override public void resetModel() { }
        @Override public void saveTiers(TierSettings value) {
            snapshot = new Snapshot(snapshot.model(), value, snapshot.embedding(),
                    snapshot.storageDescription());
        }
        @Override public void saveEmbedding(EmbeddingSettings value) {
            snapshot = new Snapshot(snapshot.model(), snapshot.tiers(), value,
                    snapshot.storageDescription());
        }
    }

    private static final class FakeProbePort implements ModelSettingsProbePort {
        @Override public ProbeResult probeModel(ModelSettings settings) {
            return new ProbeResult(true, "ok");
        }
        @Override public ProbeResult probeEmbedding(
                EmbeddingSettings form, EmbeddingSettings persisted) {
            return new ProbeResult(true, "ok");
        }
    }

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public void notify(ToastRequest request) { }
    }
}
