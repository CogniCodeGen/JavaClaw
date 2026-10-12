package com.javaclaw.ui.javafx.theme;

import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.Region;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 顶栏主题菜单 Controller：管理 FXML 菜单项及主题监听器的生命周期。 */
public final class ThemeMenuController implements AutoCloseable {

    @FXML private MenuButton root;
    @FXML private Region themeSwatch;

    private final ThemeSelectionService themes;
    private final ThemeMenuEntryFactory entries;
    private final List<ThemeMenuEntryView> loadedEntries = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ChangeListener<String> themeListener;

    @Autowired
    public ThemeMenuController(
            ThemeSelectionService themes,
            ThemeMenuEntryFactory entries) {
        this.themes = Objects.requireNonNull(themes, "themes");
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @FXML
    private void initialize() {
        refreshSwatch();
        themeListener = (ignored, previous, current) -> refreshSwatch();
        themes.currentThemeProperty().addListener(themeListener);
    }

    @FXML
    private void showing() {
        if (!closed.get()) rebuildEntries();
    }

    void rebuildEntries() {
        root.getItems().clear();
        closeEntries();
        String current = themes.currentThemeId();
        for (ThemeOption option : themes.availableThemes()) {
            ThemeMenuEntryView entry = entries.create(
                    option, option.id().equals(current), () -> themes.select(option.id()));
            loadedEntries.add(entry);
            root.getItems().add(entry.root());
        }
    }

    private void refreshSwatch() {
        themeSwatch.setStyle("-fx-background-color: "
                + themes.currentTheme().brand() + "; -fx-background-radius: 4;");
    }

    /** 工作区完成切换后，从新工作区配置重新应用主题。 */
    public void reloadFromWorkspace() {
        if (!closed.get()) themes.reloadFromWorkspace();
    }

    private void closeEntries() {
        RuntimeException failure = null;
        for (int index = loadedEntries.size() - 1; index >= 0; index--) {
            try {
                loadedEntries.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        loadedEntries.clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (themeListener != null) {
            themes.currentThemeProperty().removeListener(themeListener);
            themeListener = null;
        }
        root.getItems().clear();
        closeEntries();
    }

    boolean isClosed() {
        return closed.get();
    }
}
