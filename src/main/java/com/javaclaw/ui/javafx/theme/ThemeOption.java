package com.javaclaw.ui.javafx.theme;

/** 顶栏主题选择器需要的不可变展示数据。 */
public record ThemeOption(
        String id,
        String name,
        String subtitle,
        String brand,
        String background,
        String surface) {
}
