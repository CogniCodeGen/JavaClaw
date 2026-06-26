package com.javaclaw.task;

import java.util.List;

/**
 * 任务通知渠道常量与标签映射
 *
 * <p>任务自动完成或因 Token 预算耗尽自动暂停时，按此枚举选择通知渠道。
 * 实际发送由 {@link com.javaclaw.notification.NotificationTools#sendByChannel(String, String, String)} 完成。</p>
 *
 * @author JavaClaw
 */
public final class TaskNotificationChannel {

    /** 不发送通知 */
    public static final String NONE = "none";
    /** 向所有已启用的渠道广播 */
    public static final String ALL = "all";
    public static final String DINGTALK = "dingtalk";
    public static final String WECHAT = "wechat";
    public static final String FEISHU = "feishu";
    public static final String EMAIL = "email";
    public static final String CUSTOM = "custom";

    /** 对话框下拉顺序 */
    public static final List<String> ORDERED_CHANNELS = List.of(
            NONE, ALL, DINGTALK, WECHAT, FEISHU, EMAIL, CUSTOM);

    /** 渠道 key → 中文显示标签 */
    public static String displayLabel(String channel) {
        if (channel == null) return "不通知";
        return switch (channel.toLowerCase()) {
            case ALL -> "全部已启用渠道";
            case DINGTALK -> "钉钉";
            case WECHAT -> "企业微信";
            case FEISHU -> "飞书";
            case EMAIL -> "邮件";
            case CUSTOM -> "自定义 Webhook";
            default -> "不通知";
        };
    }

    /** 中文显示标签 → 渠道 key（无法匹配时返回 {@link #NONE}） */
    public static String fromLabel(String label) {
        if (label == null) return NONE;
        return switch (label) {
            case "全部已启用渠道" -> ALL;
            case "钉钉" -> DINGTALK;
            case "企业微信" -> WECHAT;
            case "飞书" -> FEISHU;
            case "邮件" -> EMAIL;
            case "自定义 Webhook" -> CUSTOM;
            default -> NONE;
        };
    }

    /** 判断是否需要发送通知（非 null 非 none） */
    public static boolean isActive(String channel) {
        return channel != null && !channel.isBlank() && !NONE.equalsIgnoreCase(channel);
    }

    private TaskNotificationChannel() {}
}
