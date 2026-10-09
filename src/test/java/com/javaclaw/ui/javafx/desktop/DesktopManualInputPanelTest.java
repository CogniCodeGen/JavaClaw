package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopTarget;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Human delivery and shutdown regressions with intentionally delayed service completions. */
@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class DesktopManualInputPanelTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private Fixture fixture;

    @BeforeAll
    static void startToolkit() throws Exception {
        java.awt.Toolkit.getDefaultToolkit();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void closePanel() throws Exception {
        if (fixture != null && fixture.stage != null) fx(() -> { fixture.stage.close(); return null; });
    }

    @Test
    void closeBeforeCommitCompletionNeverDispatchesAndReleasesAndClearsThePanel() throws Exception {
        fixture = new Fixture();
        fixture.openAndSubmit();
        assertEquals(0, fixture.performCalls.get());
        fixture.closeAndAssertCleared();
        fixture.commit.complete(true);
        flushFx();
        assertEquals(0, fixture.performCalls.get());
        assertEquals(0, fixture.acknowledgements.get());
        assertEquals(1, fixture.releases.get());
    }

    @Test
    void closeDuringDispatchMarksUncertainAndLateVerifiedResultNeverAcknowledgesOrRetries() throws Exception {
        fixture = new Fixture();
        fixture.openAndSubmit();
        fixture.commit.complete(true);
        assertEquals(1, fixture.performCalls.get());
        fixture.closeAndAssertCleared();
        assertTrue(fixture.uncertain.get() >= 1);
        fixture.performed.complete(verified());
        flushFx();
        assertEquals(1, fixture.performCalls.get());
        assertEquals(0, fixture.acknowledgements.get());
        assertEquals(1, fixture.releases.get());
        assertFalse(fx(fixture.stage::isShowing));
    }

    @Test
    void normalResultIsAcknowledgedOnlyAfterTheFxPanelDisplaysIt() throws Exception {
        fixture = new Fixture();
        fixture.openAndSubmit();
        fixture.commit.complete(true);
        assertEquals(1, fixture.performCalls.get());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Platform.runLater(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            fixture.performed.complete(verified());
            assertEquals(0, fixture.acknowledgements.get(), "native return is not yet a displayed human result");
        } finally { release.countDown(); }
        flushFx();
        assertEquals(1, fixture.acknowledgements.get());
        assertTrue(fixture.acknowledgedOnFx.get());
        assertTrue(fixture.statusVisibleAtAcknowledgement.get());
        assertEquals(0, fixture.uncertain.get());
        assertTrue(fx(() -> fixture.status().getText().contains("VERIFIED")));
        fixture.closeAndAssertCleared();
        assertEquals(0, fixture.uncertain.get(), "closing after a displayed result must not create uncertainty");
    }

    @Test
    void systemInputPanelAlsoPausesAutomationButDoesNotMislabelKeyboardInsertionAsAControlWrite() throws Exception {
        fixture = new Fixture(DesktopInputPolicy.SYSTEM_EXPLICIT);
        fixture.open();
        assertEquals(1, fixture.acquires.get());
        assertTrue(fx(() -> fixture.status().getText().contains("自动输入已暂停")));
        assertTrue(fx(() -> fixture.text().isDisabled()));
        assertTrue(fx(() -> fixture.submit().isDisabled()));
        assertEquals(0, fixture.performCalls.get());
        fixture.closeAndAssertCleared();
        assertEquals(1, fixture.releases.get());
    }

    private static DesktopActionResult verified() {
        return new DesktopActionResult(DesktopActionResult.Status.VERIFIED, "文本控件已读回", 4);
    }

    private static final class Fixture {
        final String sessionId = "manual-panel-" + IDS.incrementAndGet();
        final DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "agent", "run");
        final DesktopTarget target = new DesktopTarget("test", "target", 9, sessionId, "Window",
                0, 0, 20, 20, DesktopTarget.VISIBLE);
        final DesktopSessionInfo info;
        final DesktopFrame frame = new DesktopFrame("target", 4, 100, 20, 20, 80, new byte[1600]);
        final DesktopElement element = new DesktopElement("text-control", "text", "Editor", 2, 2, 12, 12,
                DesktopElement.INSERT_TEXT | DesktopElement.SET_TEXT);
        final DesktopObservation observation = new DesktopObservation(sessionId, "observation", frame, List.of(element));
        final CompletableFuture<Boolean> commit = new CompletableFuture<>();
        final CompletableFuture<DesktopActionResult> performed = new CompletableFuture<>();
        final AtomicInteger performCalls = new AtomicInteger();
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger uncertain = new AtomicInteger();
        final AtomicInteger acknowledgements = new AtomicInteger();
        final AtomicBoolean acknowledgedOnFx = new AtomicBoolean();
        final AtomicBoolean statusVisibleAtAcknowledgement = new AtomicBoolean();
        Stage stage;
        final DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[] {DesktopSessionService.class},
                (proxy, method, args) -> {
                    if (args != null && args.length >= 2) {
                        assertEquals(owner, args[0]);
                        assertEquals(sessionId, args[1]);
                    }
                    return switch (method.getName()) {
                        case "acquireManualControl" -> {
                            acquires.incrementAndGet(); yield CompletableFuture.completedFuture("lease");
                        }
                        case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(observation));
                        case "commitObservation" -> commit;
                        case "performManual" -> {
                            assertEquals("lease", args[2]); performCalls.incrementAndGet(); yield performed;
                        }
                        case "releaseManualControl" -> {
                            assertEquals("lease", args[2]); releases.incrementAndGet();
                            yield CompletableFuture.completedFuture(null);
                        }
                        case "markDeliveryUncertain" -> { uncertain.incrementAndGet(); yield null; }
                        case "acknowledgeActionResult" -> {
                            acknowledgements.incrementAndGet();
                            acknowledgedOnFx.set(Platform.isFxApplicationThread());
                            statusVisibleAtAcknowledgement.set(status().getText().contains("VERIFIED"));
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });

        Fixture() { this(DesktopInputPolicy.BACKGROUND_STRICT); }
        Fixture(DesktopInputPolicy policy) {
            info = new DesktopSessionInfo(sessionId, target, true,
                    policy == DesktopInputPolicy.SYSTEM_EXPLICIT, policy);
        }

        void open() throws Exception {
            fx(() -> {
                DesktopManualInputPanel.open(owner, info, service);
                stage = Window.getWindows().stream().filter(window -> window instanceof Stage candidate
                        && candidate.getTitle().equals("人工文本输入 · " + sessionId))
                        .map(window -> (Stage) window).findFirst().orElseThrow();
                return null;
            });
        }

        void openAndSubmit() throws Exception {
            fx(() -> {
                DesktopManualInputPanel.open(owner, info, service);
                stage = Window.getWindows().stream().filter(window -> window instanceof Stage candidate
                        && candidate.getTitle().equals("人工文本输入 · " + sessionId))
                        .map(window -> (Stage) window).findFirst().orElseThrow();
                assertTrue(stage.isShowing());
                assertTrue(image().getImage() != null);
                assertEquals(1, elements().getItems().size());
                text().setText("Only this explicit human submission");
                assertFalse(submit().isDisabled());
                submit().fire();
                assertTrue(submit().isDisabled());
                return null;
            });
        }

        void closeAndAssertCleared() throws Exception {
            fx(() -> {
                stage.close();
                assertFalse(stage.isShowing());
                assertEquals("", text().getText());
                assertTrue(elements().getItems().isEmpty());
                assertNull(image().getImage());
                return null;
            });
        }
        TextArea text() { return (TextArea) stage.getScene().lookup("#desktop-manual-text"); }
        Button submit() { return (Button) stage.getScene().lookup("#desktop-manual-submit"); }
        ComboBox<?> elements() { return (ComboBox<?>) stage.getScene().lookup("#desktop-manual-elements"); }
        ImageView image() { return (ImageView) stage.getScene().lookup("#desktop-manual-image"); }
        Label status() { return (Label) ((VBox) stage.getScene().getRoot()).getChildren().getLast(); }
    }

    private static void flushFx() throws Exception {
        fx(() -> null);
        fx(() -> null);
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) return task.call();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { value.set(task.call()); }
            catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "JavaFX operation timed out");
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
