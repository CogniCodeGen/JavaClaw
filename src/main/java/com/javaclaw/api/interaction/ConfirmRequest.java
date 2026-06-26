package com.javaclaw.api.interaction;

/**
 * 向用户发起一次操作确认的请求。
 *
 * @param toolName       触发确认的工具名称（如 {@code sys_file_delete}）
 * @param riskLabel      风险等级的人类可读标签（如 "高危"、"不可逆"），UI 可用于标题或徽章
 * @param description    向用户展示的操作详情（可含多行）
 * @param kind           确认强度；UI 据此选择不同的交互形式
 * @param timeoutSeconds 等待用户响应的秒数；超时视为拒绝。{@code <= 0} 视为无超时
 * @param keyword        {@link ConfirmKind#DOUBLE_CONFIRM} 时要求用户键入的关键词
 * @param managedTask    是否处于托管任务场景（UI 可做样式区分）
 */
public record ConfirmRequest(
        String toolName,
        String riskLabel,
        String description,
        ConfirmKind kind,
        int timeoutSeconds,
        String keyword,
        boolean managedTask
) {

    public ConfirmRequest {
        if (toolName == null) toolName = "";
        if (riskLabel == null) riskLabel = "";
        if (description == null) description = "";
        if (kind == null) kind = ConfirmKind.CONFIRM;
        if (keyword == null) keyword = "";
    }
}
