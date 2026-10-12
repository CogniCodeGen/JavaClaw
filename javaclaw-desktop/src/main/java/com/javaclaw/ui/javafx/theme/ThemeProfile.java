package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;

import java.util.Objects;

/**
 * 主题系统提供给只读渲染组件的稳定视图。
 *
 * <p>所有方法只能在 JavaFX Application Thread 调用。主题切换完成后，实现先更新
 * {@link #themeProperty()}，再递增 {@link #revisionProperty()}。</p>
 */
public interface ThemeProfile {

    /** Canvas 等无法使用 CSS looked-up color 的渲染器所需的最小调色板。 */
    record Palette(String brand, String background, String surface) {

        public Palette {
            Objects.requireNonNull(brand, "brand");
            Objects.requireNonNull(background, "background");
            Objects.requireNonNull(surface, "surface");
        }
    }

    ReadOnlyStringProperty themeProperty();

    ReadOnlyIntegerProperty revisionProperty();

    Palette currentPalette();
}
