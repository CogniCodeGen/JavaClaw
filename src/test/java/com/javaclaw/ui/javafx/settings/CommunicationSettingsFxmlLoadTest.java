package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailProbeResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class CommunicationSettingsFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<SettingsSectionView<?>> views = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
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
        runFx(() -> {
            for (int index = views.size() - 1; index >= 0; index--) views.get(index).close();
            views.clear();
        });
        if (context != null) context.close();
    }

    @Test
    void emailSectionLoadsReusableSecretAndAppliesPresetThroughItsViewModel()
            throws Exception {
        prepareContext();
        var email = add(callFx(() -> context.getBean(CommunicationSettingsSectionFactory.class)
                .createEmail(ignored -> { })));
        runFx(() -> render(email));

        assertEquals("smtp.qq.com", callFx(() -> text(email, "smtpHostField").getText()));
        PasswordField secret = callFx(() ->
                (PasswordField) email.root().lookup("#secretField"));
        assertNotNull(secret);
        assertEquals("mail-secret", callFx(secret::getText));

        runFx(() -> combo(email, "presetCombo").setValue("Gmail"));
        assertEquals("smtp.gmail.com", callFx(() -> text(email, "smtpHostField").getText()));
        assertEquals("587", callFx(() -> text(email, "smtpPortField").getText()));

        runFx(email::close);
        views.remove(email);
        assertEquals("", callFx(secret::getText));
    }

    @Test
    void asyncSaveAndProbeUpdateApplicationStateAndAppliedCallback() throws Exception {
        prepareContext();
        AtomicInteger applied = new AtomicInteger();
        var email = add(callFx(() -> context.getBean(CommunicationSettingsSectionFactory.class)
                .createEmail(ignored -> applied.incrementAndGet())));
        runFx(() -> render(email));
        AtomicReference<EmailProbeResult> probe = new AtomicReference<>();

        runFx(() -> {
            text(email, "usernameField").setText("changed@example.com");
            email.controller().probe(probe::set, failure -> {
                throw new AssertionError(failure);
            });
        });

        awaitFx(() -> probe.get() != null && applied.get() == 1);
        assertTrue(probe.get().succeeded());
        assertEquals("changed@example.com", service.snapshot.email().username());
    }

    @Test
    void notificationSectionReflectsEnabledStateAndSavesImmutableForm() throws Exception {
        prepareContext();
        var notifications = add(callFx(() ->
                context.getBean(CommunicationSettingsSectionFactory.class)
                        .createNotifications(ignored -> { })));
        runFx(() -> render(notifications));

        assertFalse(callFx(() -> text(notifications, "wechatWebhookField").isDisabled()));
        assertTrue(callFx(() -> text(notifications, "customWebhookField").isDisabled()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runFx(() -> {
            text(notifications, "wechatWebhookField").setText("https://new.example/hook");
            notifications.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> service.notificationSaves == 1 || failure.get() != null);

        assertNull(failure.get());
        assertEquals("https://new.example/hook",
                service.snapshot.notifications().wechatWebhook());
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService(snapshot());
        context.registerBean(CommunicationSettingsApplicationService.class, () -> service);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(CommunicationSettingsSectionFactory.class,
                () -> new CommunicationSettingsSectionFactory(
                        context.getBean(SpringFxmlLoader.class)));
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

    @SuppressWarnings("unchecked")
    private static ComboBox<String> combo(SettingsSectionView<?> view, String id) {
        return (ComboBox<String>) view.root().lookup("#" + id);
    }

    private static Snapshot snapshot() {
        return new Snapshot(
                new EmailSettings("smtp.qq.com", 465, "imap.qq.com", 993,
                        "owner@example.com", "mail-secret", "owner@example.com",
                        Encryption.SSL),
                new NotificationSettings(false, "", "", true,
                        "https://wechat.example/hook", false, "", "",
                        false, "", false, "", "{\"content\":\"${message}\"}"),
                "fake-h2");
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

    private static final class FakeService implements CommunicationSettingsApplicationService {
        private volatile Snapshot snapshot;
        private volatile int notificationSaves;

        private FakeService(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public SaveResult saveEmail(EmailSettings value) {
            snapshot = new Snapshot(value, snapshot.notifications(), snapshot.storageDescription());
            return new SaveResult(snapshot, "✓ 已保存");
        }
        @Override public EmailProbeResult saveAndProbeEmail(EmailSettings value) {
            SaveResult saved = saveEmail(value);
            return new EmailProbeResult(saved, true, "✓ SMTP 已连接 · IMAP 已连接");
        }
        @Override public SaveResult saveNotifications(NotificationSettings value) {
            notificationSaves++;
            snapshot = new Snapshot(snapshot.email(), value, snapshot.storageDescription());
            return new SaveResult(snapshot, "✓ 已保存");
        }
    }
}
