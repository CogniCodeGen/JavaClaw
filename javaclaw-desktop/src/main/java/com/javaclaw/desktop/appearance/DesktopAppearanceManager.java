package com.javaclaw.desktop.appearance;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * 协调所有 Desktop Scene 的外观预览、保存和取消。
 *
 * <p>Scene 仅以弱引用登记，窗口关闭后不会被外观服务持有。预览只改变节点 CSS class；只有 {@link #save(AppearancePreferences)} 会写本地偏好。
 *
 * <p><strong>线程约束：</strong>Scene 登记和外观变更必须在 JavaFX Application Thread 调用；窗口监听确保预览期间新打开的 Scene 先应用当前预览再显示。
 */
public final class DesktopAppearanceManager {
    private final AppearancePreferenceStore store;
    private final List<WeakReference<Scene>> scenes = new ArrayList<>();
    private final ListChangeListener<Window> windowListener = this::windowsChanged;
    private final WeakListChangeListener<Window> weakWindowListener = new WeakListChangeListener<>(windowListener);
    private AppearancePreferences saved;
    private AppearancePreferences applied;
    private boolean observingWindows;

    /**
     * 创建外观协调器并读取已保存偏好。
     *
     * @param store 本地偏好存储
     */
    public DesktopAppearanceManager(AppearancePreferenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
        saved = Objects.requireNonNull(store.load(), "stored preferences");
        applied = saved;
    }

    /**
     * 登记 Scene 并立即应用当前预览。
     *
     * @param scene Desktop 管理的 Scene
     */
    public void register(Scene scene) {
        Scene checked = Objects.requireNonNull(scene, "scene");
        observeWindows();
        compactScenes();
        if (scenes.stream().map(WeakReference::get).noneMatch(checked::equals)) {
            scenes.add(new WeakReference<>(checked));
        }
        apply(checked, applied);
    }

    /**
     * 返回最后一次成功保存的偏好。
     *
     * @return 已保存偏好
     */
    public AppearancePreferences saved() {
        return saved;
    }

    /**
     * 返回当前正在展示的偏好，可能是尚未保存的预览。
     *
     * @return 当前外观
     */
    public AppearancePreferences applied() {
        return applied;
    }

    /**
     * 在全部已登记 Scene 预览偏好，不执行持久化。
     *
     * @param preferences 完整预览值
     */
    public void preview(AppearancePreferences preferences) {
        applied = Objects.requireNonNull(preferences, "preferences");
        applyToScenes();
    }

    /**
     * 持久化并应用一组完整偏好。
     *
     * @param preferences 完整保存值
     */
    public void save(AppearancePreferences preferences) {
        AppearancePreferences checked = Objects.requireNonNull(preferences, "preferences");
        store.save(checked);
        saved = checked;
        applied = checked;
        applyToScenes();
    }

    /** 取消尚未保存的预览并恢复最后一次保存值。 */
    public void cancelPreview() {
        applied = saved;
        applyToScenes();
    }

    private void applyToScenes() {
        discoverOpenScenes();
        compactScenes();
        scenes.stream().map(WeakReference::get).filter(Objects::nonNull).forEach(scene -> apply(scene, applied));
    }

    private void compactScenes() {
        scenes.removeIf(reference -> reference.get() == null);
    }

    private void discoverOpenScenes() {
        Window.getWindows().stream()
                .map(Window::getScene)
                .filter(Objects::nonNull)
                .filter(candidate -> scenes.stream().map(WeakReference::get).noneMatch(candidate::equals))
                .map(WeakReference::new)
                .forEach(scenes::add);
    }

    private void observeWindows() {
        if (!observingWindows) {
            Window.getWindows().addListener(weakWindowListener);
            observingWindows = true;
        }
    }

    private void windowsChanged(ListChangeListener.Change<? extends Window> change) {
        while (change.next()) {
            if (change.wasAdded()) {
                change.getAddedSubList().stream()
                        .map(Window::getScene)
                        .filter(Objects::nonNull)
                        .forEach(this::register);
            }
        }
    }

    /**
     * 将一组外观 class 应用到单个 Scene，并移除上一次外观残留。
     *
     * @param scene 目标 Scene
     * @param preferences 完整外观偏好
     */
    public static void apply(Scene scene, AppearancePreferences preferences) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(preferences, "preferences");
        Parent root = scene.getRoot();
        root.getStyleClass().removeIf(DesktopAppearanceManager::isAppearanceClass);
        root.getStyleClass()
                .addAll(
                        preferences.theme().cssClass(),
                        preferences.fontScale().cssClass(),
                        preferences.density().cssClass());
        root.applyCss();
    }

    private static boolean isAppearanceClass(String value) {
        return value.startsWith("theme-") || value.startsWith("font-scale-") || value.startsWith("density-");
    }
}
