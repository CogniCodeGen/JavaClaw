package com.javaclaw.desktop.appearance;

/** JavaClaw 509f197 设计体系提供的九种界面主题。 */
public enum AppearanceTheme {
    /** 默认翡翠主题。 */
    EMERALD("emerald", "翡翠", "温和、清晰的默认浅色主题"),
    /** 午夜深色主题。 */
    MIDNIGHT("midnight", "午夜", "暖黑背景与翡翠强调色"),
    /** 蓝宝石主题。 */
    SAPPHIRE("sapphire", "蓝宝石", "冷蓝色专业浅色主题"),
    /** 石墨主题。 */
    GRAPHITE("graphite", "石墨", "克制的中性灰主题"),
    /** 陶土主题。 */
    TERRACOTTA("terracotta", "陶土", "温暖的陶土色主题"),
    /** 碳黑深色主题。 */
    CARBON("carbon", "碳黑", "冷黑背景与蓝色强调色"),
    /** 海洋主题。 */
    OCEAN("ocean", "海洋", "清爽的青绿色主题"),
    /** 梅紫主题。 */
    PLUM("plum", "梅紫", "柔和的紫色浅色主题"),
    /** 蜂蜜主题。 */
    HONEY("honey", "蜂蜜", "温暖的琥珀色主题");

    private final String id;
    private final String displayName;
    private final String description;

    AppearanceTheme(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * 返回持久化标识。
     *
     * @return 稳定的小写标识
     */
    public String id() {
        return id;
    }

    /**
     * 返回界面名称。
     *
     * @return 简短中文名称
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 返回主题说明。
     *
     * @return 面向用户的主题说明
     */
    public String description() {
        return description;
    }

    /**
     * 返回根节点使用的 CSS class。
     *
     * @return 主题 CSS class
     */
    public String cssClass() {
        return "theme-" + id;
    }

    /**
     * 从持久化值解析主题；非法值回退默认主题。
     *
     * @param value 持久化值，可空
     * @return 已知主题
     */
    public static AppearanceTheme fromId(String value) {
        if (value != null) {
            for (AppearanceTheme theme : values()) {
                if (theme.id.equals(value)) {
                    return theme;
                }
            }
        }
        return EMERALD;
    }
}
