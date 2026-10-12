package com.javaclaw.testsupport;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.theme.FontProfile;
import com.javaclaw.ui.javafx.theme.ThemeProfile;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Shared lightweight appearance beans for focused FXML tests. */
public final class FxmlTestBeans {

    private FxmlTestBeans() {
    }

    public static void register(AnnotationConfigApplicationContext context) {
        var beans = context.getBeanFactory();
        if (beans.getBeanNamesForType(FxDispatcher.class).length == 0) {
            context.registerBean(FxDispatcher.class, FxDispatcher::new);
        }
        if (beans.getBeanNamesForType(UIHelper.class).length == 0) {
            context.registerBean(UIHelper.class,
                    () -> new UIHelper(context.getBean(FxDispatcher.class)));
        }
        context.registerBean(FontProfile.class, StaticFontProfile::new);
        context.registerBean(ThemeProfile.class, StaticThemeProfile::new);
    }

    private static final class StaticFontProfile implements FontProfile {
        private final SimpleIntegerProperty revision = new SimpleIntegerProperty();

        @Override public double chatFontPx() { return 14.5; }
        @Override public double chatLineHeight() { return 1.65; }
        @Override public String uiStack() { return "System, sans-serif"; }
        @Override public String monoStack() { return "Monospaced, monospace"; }
        @Override public ReadOnlyIntegerProperty revisionProperty() { return revision; }
    }

    private static final class StaticThemeProfile implements ThemeProfile {
        private final SimpleStringProperty theme = new SimpleStringProperty("test");
        private final SimpleIntegerProperty revision = new SimpleIntegerProperty();

        @Override public ReadOnlyStringProperty themeProperty() { return theme; }
        @Override public ReadOnlyIntegerProperty revisionProperty() { return revision; }
        @Override public Palette currentPalette() {
            return new Palette("#2E9A6A", "#FBFAF6", "#FFFFFF");
        }
    }
}
