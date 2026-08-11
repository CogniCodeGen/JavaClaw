package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.ui.javafx.theme.FontSelectionService;
import com.javaclaw.ui.javafx.theme.FontSelectionService.DensityOption;
import com.javaclaw.ui.javafx.theme.FontSelectionService.FontOption;
import com.javaclaw.ui.javafx.theme.FontSelectionService.MonoOption;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Scene;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class AppearanceSettingsFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<SettingsSectionView<?>> views = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private FakeThemes themes;
    private FakeFonts fonts;

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
    void themeCardsLoadTheirReusableFxmlAndApplySelectionImmediately() throws Exception {
        prepareContext();
        var appearance = add(callFx(() -> context.getBean(AppearanceSettingsSectionFactory.class)
                .createAppearance()));
        runFx(() -> render(appearance));

        ThemeCard midnight = callFx(() ->
                (ThemeCard) appearance.root().lookup("#midnightCard"));
        runFx(() -> midnight.getOnMouseClicked().handle(null));

        assertEquals("midnight", themes.currentThemeId());
        assertEquals("midnight", callFx(() -> appearance.controller().viewModel()
                .currentThemeIdProperty().get()));
        assertTrue(callFx(() -> ((ThemeCard) appearance.root()
                .lookup("#graphiteCard")).isVisible()));
    }

    @Test
    void fontCardsAndFixedFxmlSegmentsReflectAvailabilityAndExternalRevisions()
            throws Exception {
        prepareContext();
        var fontView = add(callFx(() -> context.getBean(AppearanceSettingsSectionFactory.class)
                .createFonts()));
        runFx(() -> render(fontView));

        assertFalse(callFx(() -> fontView.root().lookup("#interFontCard").isVisible()));
        FontCard system = callFx(() ->
                (FontCard) fontView.root().lookup("#systemFontCard"));
        runFx(() -> system.getOnMouseClicked().handle(null));
        assertEquals("system", fonts.currentFontId());

        ToggleButton compact = callFx(() ->
                (ToggleButton) fontView.root().lookup("#compactDensityButton"));
        runFx(compact::fire);
        assertEquals("compact", fonts.currentDensityId());

        runFx(() -> fonts.externalMonospace("sfmono"));
        assertTrue(callFx(() -> ((ToggleButton) fontView.root()
                .lookup("#sfmonoMonoButton")).isSelected()));
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        themes = new FakeThemes();
        fonts = new FakeFonts();
        context.registerBean(ThemeSelectionService.class, () -> themes);
        context.registerBean(FontSelectionService.class, () -> fonts);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(AppearanceSettingsSectionFactory.class,
                () -> new AppearanceSettingsSectionFactory(
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

    private static final class FakeThemes implements ThemeSelectionService {
        private final List<ThemeOption> options = List.of(
                theme("emerald", "翡翠"), theme("midnight", "午夜"),
                theme("carbon", "碳黑"), theme("sapphire", "蓝宝石"),
                theme("ocean", "海洋"), theme("plum", "梅紫"),
                theme("terracotta", "陶土"), theme("honey", "蜂蜜"),
                theme("graphite", "石墨"));
        private final ReadOnlyStringWrapper current = new ReadOnlyStringWrapper("emerald");
        @Override public List<ThemeOption> availableThemes() { return options; }
        @Override public ThemeOption currentTheme() {
            return options.stream().filter(value -> value.id().equals(current.get()))
                    .findFirst().orElseThrow();
        }
        @Override public String currentThemeId() { return current.get(); }
        @Override public ReadOnlyStringProperty currentThemeProperty() {
            return current.getReadOnlyProperty();
        }
        @Override public void select(String themeId) { current.set(themeId); }
        @Override public void reloadFromWorkspace() { }
        private static ThemeOption theme(String id, String name) {
            return new ThemeOption(id, name, "测试", "#111", "#222", "#333");
        }
    }

    private static final class FakeFonts implements FontSelectionService {
        private final ReadOnlyIntegerWrapper revision = new ReadOnlyIntegerWrapper();
        private String font = "native";
        private String mono = "native";
        private String density = "cozy";

        @Override public List<FontOption> availableFonts() {
            return List.of(font("native", "系统原生"), font("system", "跟随系统 UI"));
        }
        @Override public List<MonoOption> availableMonospaceFonts() {
            return List.of(new MonoOption("native", "系统等宽", "monospace"),
                    new MonoOption("sfmono", "SF Mono", "SF Mono"));
        }
        @Override public List<DensityOption> availableDensities() {
            return List.of(new DensityOption("compact", "紧凑", 13.5, 1.55),
                    new DensityOption("cozy", "适中", 14.5, 1.65),
                    new DensityOption("relaxed", "宽松", 15.5, 1.8));
        }
        @Override public String currentFontId() { return font; }
        @Override public String currentMonospaceFontId() { return mono; }
        @Override public String currentDensityId() { return density; }
        @Override public ReadOnlyIntegerProperty revisionProperty() {
            return revision.getReadOnlyProperty();
        }
        @Override public void selectFont(String id) { font = id; changed(); }
        @Override public void selectMonospaceFont(String id) { mono = id; changed(); }
        @Override public void selectDensity(String id) { density = id; changed(); }
        @Override public void reloadFromWorkspace() { changed(); }
        private void externalMonospace(String id) { mono = id; changed(); }
        private void changed() { revision.set(revision.get() + 1); }
        private static FontOption font(String id, String name) {
            return new FontOption(id, name, "测试", "System");
        }
    }
}
