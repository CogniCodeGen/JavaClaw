package com.javaclaw.desktop.appearance;

/** Desktop 支持的有限字号比例，避免任意数值破坏布局。 */
public enum FontScale {
    /** 90% 字号。 */
    SMALL(90, "较小"),
    /** 100% 标准字号。 */
    STANDARD(100, "标准"),
    /** 110% 较大字号。 */
    LARGE(110, "较大"),
    /** 120% 最大字号。 */
    EXTRA_LARGE(120, "最大");

    private final int percent;
    private final String displayName;

    FontScale(int percent, String displayName) {
        this.percent = percent;
        this.displayName = displayName;
    }

    /**
     * 返回百分比整数。
     *
     * @return 90、100、110 或 120
     */
    public int percent() {
        return percent;
    }

    /**
     * 返回界面名称。
     *
     * @return 中文名称
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 返回根节点使用的 CSS class。
     *
     * @return 字号 CSS class
     */
    public String cssClass() {
        return "font-scale-" + percent;
    }

    /**
     * 从持久化数值解析字号；非法值回退 100%。
     *
     * @param value 持久化百分比
     * @return 已知字号
     */
    public static FontScale fromPercent(int value) {
        for (FontScale scale : values()) {
            if (scale.percent == value) {
                return scale;
            }
        }
        return STANDARD;
    }
}
