package com.javaclaw.desktop.appearance;

/** Desktop 控件和页面留白密度。 */
public enum InterfaceDensity {
    /** 更紧凑的列表和表单。 */
    COMPACT("compact", "紧凑"),
    /** 默认留白。 */
    STANDARD("standard", "标准"),
    /** 更宽松的留白。 */
    SPACIOUS("spacious", "舒展");

    private final String id;
    private final String displayName;

    InterfaceDensity(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
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
     * @return 中文名称
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 返回根节点使用的 CSS class。
     *
     * @return 密度 CSS class
     */
    public String cssClass() {
        return "density-" + id;
    }

    /**
     * 从持久化值解析密度；非法值回退标准密度。
     *
     * @param value 持久化值，可空
     * @return 已知密度
     */
    public static InterfaceDensity fromId(String value) {
        if (value != null) {
            for (InterfaceDensity density : values()) {
                if (density.id.equals(value)) {
                    return density;
                }
            }
        }
        return STANDARD;
    }
}
