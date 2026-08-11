package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.Snapshot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class BehaviorSettingsFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<SettingsSectionView<?>> views = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private FakeService service;
    private FakeThemes themes;

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
    void allSectionsLoadAndGepaAdaptiveStateTracksItsViewModel() throws Exception {
        prepareContext();
        var factory = context.getBean(BehaviorSettingsSectionFactory.class);
        var gepa = add(callFx(() -> factory.createGepa(ignored -> { })));
        var skill = add(callFx(() -> factory.createSkillEvolution(ignored -> { })));
        var general = add(callFx(() -> factory.createGeneral(ignored -> { })));
        runFx(() -> {
            render(gepa);
            render(skill);
            render(general);
        });

        TextField rounds = callFx(() -> text(gepa, "feedbackMaxRoundsField"));
        assertFalse(callFx(rounds::isDisabled));
        runFx(() -> toggle(gepa, "adaptivePlanningCheck").setSelected(false));
        assertTrue(callFx(rounds::isDisabled));
        assertNotNull(callFx(() -> combo(general, "themeCombo").getValue()));
    }

    @Test
    void skillModeUpdatesHintAndAsyncSavePersistsImmutableForm() throws Exception {
        prepareContext();
        var skill = add(callFx(() -> context.getBean(BehaviorSettingsSectionFactory.class)
                .createSkillEvolution(ignored -> { })));
        runFx(() -> render(skill));

        runFx(() -> ((ToggleButton) skill.root().lookup("#autoModeButton")).fire());
        assertTrue(callFx(() -> ((Label) skill.root().lookup("#modeHintLabel"))
                .getText().contains("直接落盘")));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runFx(() -> {
            text(skill, "minimumToolCallsField").setText("9");
            skill.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> service.skillSaves == 1 || failure.get() != null);

        assertNull(failure.get());
        assertEquals("auto", service.snapshot.skillEvolution().mode());
        assertEquals(9, service.snapshot.skillEvolution().minimumToolCalls());
    }

    @Test
    void generalThemeAppliesImmediatelyWhileBehaviorUsesAsyncSave() throws Exception {
        prepareContext();
        var general = add(callFx(() -> context.getBean(BehaviorSettingsSectionFactory.class)
                .createGeneral(ignored -> { })));
        runFx(() -> render(general));

        runFx(() -> combo(general, "themeCombo").setValue(themes.options.get(1)));
        assertEquals("midnight", themes.currentThemeId());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runFx(() -> {
            toggle(general, "minimizeToTrayCheck").setSelected(false);
            toggle(general, "taskRiskAutoApproveCheck").setSelected(true);
            general.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> service.generalSaves == 1 || failure.get() != null);

        assertNull(failure.get());
        assertEquals(new GeneralSettings(false, true), service.snapshot.general());
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService(snapshot());
        themes = new FakeThemes();
        context.registerBean(BehaviorSettingsApplicationService.class, () -> service);
        context.registerBean(ThemeSelectionService.class, () -> themes);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(BehaviorSettingsSectionFactory.class,
                () -> new BehaviorSettingsSectionFactory(
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

    private static ToggleSwitch toggle(SettingsSectionView<?> view, String id) {
        return (ToggleSwitch) view.root().lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ThemeOption> combo(SettingsSectionView<?> view, String id) {
        return (ComboBox<ThemeOption>) view.root().lookup("#" + id);
    }

    private static Snapshot snapshot() {
        return new Snapshot(new GepaSettings(true, 3, 3.5, true, 2),
                new SkillEvolutionSettings("suggest", 5, 0.6, true, false),
                new GeneralSettings(true, false), "fake-agent-config");
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

    private static final class FakeService implements BehaviorSettingsApplicationService {
        private volatile Snapshot snapshot;
        private volatile int skillSaves;
        private volatile int generalSaves;

        private FakeService(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public SaveResult saveGepa(GepaSettings value) {
            snapshot = new Snapshot(value, snapshot.skillEvolution(), snapshot.general(),
                    snapshot.storageDescription());
            return new SaveResult(snapshot, "✓ 已保存", true);
        }
        @Override public SaveResult saveSkillEvolution(SkillEvolutionSettings value) {
            skillSaves++;
            snapshot = new Snapshot(snapshot.gepa(), value, snapshot.general(),
                    snapshot.storageDescription());
            return new SaveResult(snapshot, "✓ 已保存", false);
        }
        @Override public SaveResult saveGeneral(GeneralSettings value) {
            generalSaves++;
            snapshot = new Snapshot(snapshot.gepa(), snapshot.skillEvolution(), value,
                    snapshot.storageDescription());
            return new SaveResult(snapshot, "✓ 已保存", false);
        }
    }

    private static final class FakeThemes implements ThemeSelectionService {
        private final List<ThemeOption> options = List.of(
                theme("emerald", "翡翠"), theme("midnight", "午夜"));
        private final ReadOnlyStringWrapper current = new ReadOnlyStringWrapper("emerald");

        @Override public List<ThemeOption> availableThemes() { return options; }
        @Override public ThemeOption currentTheme() {
            return options.stream().filter(option -> option.id().equals(current.get()))
                    .findFirst().orElseThrow();
        }
        @Override public String currentThemeId() { return current.get(); }
        @Override public ReadOnlyStringProperty currentThemeProperty() {
            return current.getReadOnlyProperty();
        }
        @Override public void select(String themeId) { current.set(themeId); }
        @Override public void reloadFromWorkspace() { }

        private static ThemeOption theme(String id, String name) {
            return new ThemeOption(id, name, "测试主题", "#000", "#111", "#222");
        }
    }
}
