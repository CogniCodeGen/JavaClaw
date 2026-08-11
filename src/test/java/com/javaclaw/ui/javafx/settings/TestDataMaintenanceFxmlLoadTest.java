package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.CleanupResult;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.ScanResult;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class TestDataMaintenanceFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private AnnotationConfigApplicationContext context;
    private SettingsSectionView<TestDataMaintenanceController> view;
    private FakeService service;

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
    void scanRendersReusableFxmlCellsAndConfirmedCleanupClearsTheList() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(MaintenanceSettingsSectionFactory.class)
                .createTestDataMaintenance());
        runFx(() -> {
            new Scene((javafx.scene.Parent) view.root(), 800, 620);
            view.root().applyCss();
            view.root().autosize();
            button("scanButton").fire();
        });

        awaitFx(() -> list().getItems().size() == 1);
        assertEquals("junit-old", callFx(() -> list().getItems().getFirst()
                .path().getFileName().toString()));
        assertTrue(callFx(() -> list().getCellFactory().call(list())
                instanceof TestDataCandidateCell),
                "ListCell 工厂必须创建会在构造时加载 FXML 的 Cell");

        runFx(() -> button("cleanupButton").fire());
        awaitFx(() -> service.cleanupCalls == 1 && list().getItems().isEmpty());
        assertEquals(1, service.cleanupCalls);
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(TestDataMaintenanceApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(TestDataCandidateCellFactory.class,
                () -> new TestDataCandidateCellFactory());
        context.registerBean(MaintenanceSettingsSectionFactory.class,
                () -> new MaintenanceSettingsSectionFactory(
                        context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private Button button(String id) {
        return (Button) view.root().lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private ListView<Candidate> list() {
        return (ListView<Candidate>) view.root().lookup("#candidatesView");
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

    private static final class FakeService implements TestDataMaintenanceApplicationService {
        private final Candidate candidate = new Candidate(Path.of("/tmp"),
                Path.of("/tmp/junit-old"), 2048);
        private volatile int cleanupCalls;

        @Override public ScanResult scan() { return new ScanResult(List.of(candidate), 2048); }
        @Override public CleanupResult cleanup(List<Candidate> candidates) {
            cleanupCalls++;
            assertEquals(List.of(candidate), candidates);
            return new CleanupResult(1);
        }
    }

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public void notify(ToastRequest request) { }
    }
}
