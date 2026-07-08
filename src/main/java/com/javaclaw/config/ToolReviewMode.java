package com.javaclaw.config;

/**
 * 工具执行审核策略。
 *
 * <p>该策略由聊天界面快速切换，并由 {@link AgentConfig} 按工作区持久化。</p>
 */
public enum ToolReviewMode {
    /** 所有受管工具操作都弹窗确认；删除等不可逆操作仍要求二次确认。 */
    MANUAL("manual", "手动审核", "所有操作都需要用户确认"),

    /** 使用工具风险等级与托管任务目录范围评估决定是否需要人工确认。 */
    SMART("smart", "智能审核", "按风险等级智能判断是否需要确认"),

    /** 所有受管工具操作静默放行，不弹确认框。 */
    AUTO("auto", "全自动", "所有操作默认同意");

    private final String id;
    private final String displayName;
    private final String description;

    ToolReviewMode(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static ToolReviewMode fromId(String id) {
        if (id != null) {
            String normalized = id.trim();
            for (ToolReviewMode mode : values()) {
                if (mode.id.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
        }
        return SMART;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
