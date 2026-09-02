package com.javaclaw.desktop.appearance;

import java.util.Objects;

/**
 * Desktop 本机外观偏好。
 *
 * @param theme 主题，不能为空
 * @param fontScale 字号比例，不能为空
 * @param density 界面密度，不能为空
 */
public record AppearancePreferences(AppearanceTheme theme, FontScale fontScale, InterfaceDensity density) {
    /** 校验全部组件，确保外观应用过程不需要空值分支。 */
    public AppearancePreferences {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(fontScale, "fontScale");
        Objects.requireNonNull(density, "density");
    }

    /**
     * 返回全新安装使用的默认外观。
     *
     * @return 翡翠、100% 字号、标准密度
     */
    public static AppearancePreferences defaults() {
        return new AppearancePreferences(AppearanceTheme.EMERALD, FontScale.STANDARD, InterfaceDensity.STANDARD);
    }
}
