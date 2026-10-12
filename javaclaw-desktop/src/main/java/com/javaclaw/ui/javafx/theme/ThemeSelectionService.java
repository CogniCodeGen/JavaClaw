package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyStringProperty;

import java.util.List;

/**
 * Presentation 层的主题选择端口。
 *
 * <p>所有调用受 JavaFX Application Thread 约束；可用主题顺序稳定，选择操作立即应用并
 * 持久化。未知 ID 的回退规则由实现统一处理。</p>
 */
public interface ThemeSelectionService {

    List<ThemeOption> availableThemes();

    ThemeOption currentTheme();

    String currentThemeId();

    ReadOnlyStringProperty currentThemeProperty();

    void select(String themeId);

    /** 工作区切换后重新读取并应用该工作区保存的主题。 */
    void reloadFromWorkspace();
}
