package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyIntegerProperty;

import java.util.List;

/**
 * Presentation 层字体选择端口。
 *
 * <p>所有方法受 JavaFX Application Thread 约束；选择立即应用到已打开窗口并持久化，修订号
 * 在样式完成应用后递增。可用字体只包含当前系统或应用包真实提供的字体。</p>
 */
public interface FontSelectionService {

    List<FontOption> availableFonts();

    List<MonoOption> availableMonospaceFonts();

    List<DensityOption> availableDensities();

    String currentFontId();

    String currentMonospaceFontId();

    String currentDensityId();

    ReadOnlyIntegerProperty revisionProperty();

    void selectFont(String id);

    void selectMonospaceFont(String id);

    void selectDensity(String id);

    void reloadFromWorkspace();

    record FontOption(String id, String name, String subtitle, String stack) { }

    record MonoOption(String id, String name, String stack) { }

    record DensityOption(String id, String name, double fontPx, double lineHeight) { }
}
