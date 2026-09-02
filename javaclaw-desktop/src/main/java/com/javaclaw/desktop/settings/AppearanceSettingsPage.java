package com.javaclaw.desktop.settings;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 本机外观设置页，支持跨 Scene 即时预览、保存和取消。 */
public final class AppearanceSettingsPage extends VBox implements ManagedSettingsPage {
    private final DesktopAppearanceManager appearance;
    private final Runnable close;
    private final Map<AppearanceTheme, ToggleButton> themeButtons = new EnumMap<>(AppearanceTheme.class);
    private final Map<FontScale, ToggleButton> scaleButtons = new EnumMap<>(FontScale.class);
    private final Map<InterfaceDensity, ToggleButton> densityButtons = new EnumMap<>(InterfaceDensity.class);
    private final ToggleGroup themes = new ToggleGroup();
    private final ToggleGroup scales = new ToggleGroup();
    private final ToggleGroup densities = new ToggleGroup();
    private final AsyncActionBar actions;
    private boolean rendering;

    /**
     * 创建外观页面。
     *
     * @param appearance 外观协调器
     * @param close 取消后关闭管理中心的动作
     */
    public AppearanceSettingsPage(DesktopAppearanceManager appearance, Runnable close) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.close = Objects.requireNonNull(close, "close");
        PlatformComponentFactory components = new PlatformComponentFactory();
        Button cancel = components.action("取消", ActionStyle.GHOST, ActionSize.NORMAL);
        cancel.setOnAction(event -> cancelAndClose());
        Button save = components.action("保存外观", ActionStyle.PRIMARY, ActionSize.NORMAL);
        save.setOnAction(event -> save());
        actions = new AsyncActionBar(cancel, save);

        Label title = new Label("外观");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label description = new Label("调整主题、字号与界面密度。所有已打开窗口会立即预览，保存后下次启动继续使用。");
        description.setWrapText(true);
        description.getStyleClass().add("sec-hint");
        getChildren().addAll(title, description, themeSection(), typographySection(), actions);
        getStyleClass().add("platform-page");
        listenForDraftChanges();
        beginEditing();
    }

    /** 从最后保存值开始一次新的编辑会话。 */
    public void beginEditing() {
        render(appearance.saved());
        appearance.cancelPreview();
        actions.show(ActionState.IDLE, "");
    }

    /** 丢弃当前预览并恢复最后保存值。 */
    public void cancelDraft() {
        appearance.cancelPreview();
        render(appearance.saved());
        actions.show(ActionState.IDLE, "");
    }

    /**
     * 返回当前控件表示的完整草稿。
     *
     * @return 外观草稿
     */
    public AppearancePreferences draft() {
        return new AppearancePreferences(selectedTheme(), selectedScale(), selectedDensity());
    }

    /** @return 当前页面根节点 */
    @Override
    public javafx.scene.Node content() {
        return this;
    }

    /** 从已保存外观开始编辑。 */
    @Override
    public void activate() {
        beginEditing();
    }

    /** @return 当前预览是否尚未保存 */
    @Override
    public boolean dirty() {
        return !draft().equals(appearance.saved());
    }

    /** 提示用户先保存或取消当前外观草稿。 */
    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "请先保存或取消外观草稿，再离开此页");
    }

    /** 丢弃外观草稿。 */
    @Override
    public void discardDraft() {
        cancelDraft();
    }

    private FormSection themeSection() {
        FormSection section = new FormSection("界面主题", "九种主题共用同一套语义令牌，不改变页面布局和交互语言。");
        FlowPane cards = new FlowPane(10, 10);
        cards.setPrefWrapLength(620);
        for (AppearanceTheme theme : AppearanceTheme.values()) {
            ToggleButton button = themeButton(theme);
            themeButtons.put(theme, button);
            cards.getChildren().add(button);
        }
        section.addFullWidth(cards);
        return section;
    }

    private FormSection typographySection() {
        FormSection section = new FormSection("字号与密度", "使用有限档位保证主窗口、管理页面和扩展页面保持清晰一致。");
        HBox scaleChoices = new HBox(6);
        scaleChoices.getStyleClass().add("platform-segmented");
        for (FontScale scale : FontScale.values()) {
            ToggleButton button = choice(scale.displayName() + " " + scale.percent() + "%", scales, scale);
            scaleButtons.put(scale, button);
            scaleChoices.getChildren().add(button);
        }
        HBox densityChoices = new HBox(6);
        densityChoices.getStyleClass().add("platform-segmented");
        for (InterfaceDensity density : InterfaceDensity.values()) {
            ToggleButton button = choice(density.displayName(), densities, density);
            densityButtons.put(density, button);
            densityChoices.getChildren().add(button);
        }
        section.addField("字号", scaleChoices);
        section.addField("界面密度", densityChoices);
        return section;
    }

    private ToggleButton themeButton(AppearanceTheme theme) {
        Label name = new Label(theme.displayName());
        name.getStyleClass().add("theme-card-name");
        Label description = new Label(theme.description());
        description.setWrapText(true);
        description.getStyleClass().add("theme-card-sub");
        VBox content = new VBox(4, name, description);
        content.setPadding(new Insets(11, 13, 11, 13));
        ToggleButton button = new ToggleButton();
        button.setGraphic(content);
        button.setToggleGroup(themes);
        button.setUserData(theme);
        button.setAccessibleText(theme.displayName() + "主题，" + theme.description());
        button.getStyleClass().add("theme-card");
        return button;
    }

    private static ToggleButton choice(String label, ToggleGroup group, Object value) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.setUserData(value);
        button.getStyleClass().add("platform-segment-button");
        return button;
    }

    private void listenForDraftChanges() {
        themes.selectedToggleProperty()
                .addListener((observable, previous, selected) -> selectionChanged(previous, selected));
        scales.selectedToggleProperty()
                .addListener((observable, previous, selected) -> selectionChanged(previous, selected));
        densities
                .selectedToggleProperty()
                .addListener((observable, previous, selected) -> selectionChanged(previous, selected));
    }

    private void selectionChanged(Toggle previous, Toggle selected) {
        if (!rendering && selected == null && previous != null) {
            previous.setSelected(true);
            return;
        }
        previewDraft();
    }

    private void previewDraft() {
        if (rendering
                || themes.getSelectedToggle() == null
                || scales.getSelectedToggle() == null
                || densities.getSelectedToggle() == null) {
            return;
        }
        AppearancePreferences draft = draft();
        appearance.preview(draft);
        if (draft.equals(appearance.saved())) {
            actions.show(ActionState.IDLE, "");
        } else {
            actions.show(ActionState.DIRTY, "外观预览尚未保存");
        }
    }

    private void save() {
        actions.show(ActionState.PENDING, "正在保存…");
        try {
            appearance.save(draft());
            actions.show(ActionState.SUCCESS, "外观已保存");
        } catch (RuntimeException failure) {
            appearance.cancelPreview();
            actions.show(ActionState.ERROR, Objects.requireNonNullElse(failure.getMessage(), "保存外观失败"));
        }
    }

    private void cancelAndClose() {
        cancelDraft();
        close.run();
    }

    private void render(AppearancePreferences preferences) {
        rendering = true;
        try {
            themeButtons.get(preferences.theme()).setSelected(true);
            scaleButtons.get(preferences.fontScale()).setSelected(true);
            densityButtons.get(preferences.density()).setSelected(true);
        } finally {
            rendering = false;
        }
    }

    private AppearanceTheme selectedTheme() {
        return (AppearanceTheme) themes.getSelectedToggle().getUserData();
    }

    private FontScale selectedScale() {
        return (FontScale) scales.getSelectedToggle().getUserData();
    }

    private InterfaceDensity selectedDensity() {
        return (InterfaceDensity) densities.getSelectedToggle().getUserData();
    }
}
